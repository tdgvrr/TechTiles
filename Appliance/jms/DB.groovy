// 

//
// History: 
//    
//    23-Mar-2020: Change to explicit commit logic 
//    01-May-2020: Add management instrumentation for Zabbix 
//    11-Nov-2020: Support isolated transactions 
//    01-Feb-2021: Multi-thread consumers (substantial rewrite) 

import groovy.io.FileType
import groovy.sql.Sql
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.Statement
import java.sql.SQLException
import java.sql.SQLWarning
import javax.sql.DataSource
import java.security.*
import java.text.*
import groovy.text.*
import java.util.regex.*
import java.util.*
import groovy.io.*
import groovy.*
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.lang.management.* 

import javax.jms.Connection
import javax.jms.TextMessage
import org.apache.commons.dbcp2.BasicDataSource
import org.apache.log4j.*
import groovy.util.logging.Log4j
import java.util.concurrent.*
import java.util.concurrent.Executors
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

import groovy.transform.* 

import static Constants.*

class WorkerMessage
{ 
   def message
   def replyto 
   def conn
}

class Alerter 
{
   private Logger     log
   private Object     j
   private Connection conn
 
   Alerter(Object jms, Logger l, javax.jms.Connection c)
   {
      this.log  = l
      this.j    = jms
      this.conn = c
   }   

   @Synchronized
   public void SendAlert(msg) 
   {
    try
    {
       println "ALERT: $msg"
       if (log) 
          log.warn "ALERT: $msg"
       def pipe = new File("/dev/alerts")
       if (pipe.exists() && pipe.canWrite())
       pipe << msg + '\n'
       def (aConn, aSess, aProducer) = j.JMSinitProducer("FM.ALERT.SERVICE", conn)
       if (!aProducer)
       {
           log.error "Alert: Can't initialize JMS context"
           return
       }
       
       def env = System.getenv()
       def text= "<Alert>\n" + 
                 "<timestamp>" + new Date().format("YYYY-MM-dd kk:mm:ss") + "</timestamp>\n" + 
                 "<message>${java.net.URLEncoder.encode(msg)}</message>\n" + 
                 "<source>${java.net.InetAddress.getLocalHost().getHostName()}</source>\n" + 
                 "<tenant>${env['TENANT']}</tenant>\n" +
                 "</Alert>"
       TextMessage message = aSess.createTextMessage((String)text)
       aProducer.send(message)
       aProducer.close() 
       aSess.close() 
    } 
    catch (Exception e)
    {
       log.warn "Exception in Alert processor - ${e.toString()}"
       e.printStackTrace()
       return
    }
 }  

 public setConnection(c)
 {
    log.info "Alerter setting new connection context"
    this.conn = c
 }  

}

class Consumer implements Runnable 
{
   private Logger              log
   private BasicDataSource     dataSource
   private Map                 dataSourceIndex
   private Map                 consumerIndex
   private final BlockingQueue queue
   private int                 id
   private int                 trans = 0
   private int                 errors = 0
   private int                 traffic = 0
   private double              avgresp = 0.0
   private boolean             JMSrestart = false
   private boolean             busy = false
   private Object              j
   private Object              parent
   private Connection          conn
   def                         pConn = null 
   def                         pSess = null
   def                         producer = null
   def                         A = null

   // Constructor 
  
   Consumer(int id, Object parent, BlockingQueue q, Object dsi, Object ci, Object j, javax.jms.Connection c, Logger log, Object a) 
   { 
      this.id = id
      this.parent = parent
      this.queue = q
      this.dataSourceIndex = dsi
      this.consumerIndex = ci
      this.j = j
      this.conn = c
      this.log = log
      this.A = a

      log.info "Consumer $id beginning initialization..."
   }

   // The thread's "main" 
   public void run() 
   {
     def           sql = null
     def           message = null
     def           replyto = null
     def           isupdate = 0
     def           ismore = 0
     def           rs = 0
     def           op
     def           db
     def           query
     def           startrow
     def           this_row
     def           numrows
     String        dbresp = "" 
     WorkerMessage transact = new WorkerMessage()

     try 
     {
       Thread.currentThread().setName("Worker-$id")
       this.consumerIndex.put("Worker-$id", this)
       (pConn, pSess, producer) = j.JMSinitProducer(null, this.conn)
       if (!producer)
       { 
           log.error "Worker error initializing JMS producer"
           println "ERROR: Can't initialize a JMS producer context in worker thread $id"
           System.exit(0) 
       }    
       log.info "Producer for worker $id is: " + producer.toString()
       log.info "Worker thread $id is now active and ready for processing"

       while (true) 
       { 
          busy = false
          log.info "Fetching next transaction - currently " + queue.size() + " queued entries" 
          transact = queue.take()
          busy = true
          message = transact.message
          replyto = transact.replyto
          if (message.contains("!!!STOP!!!"))
          {
             log.info "Worker $id stopping by request"
             return
          }
 
          if (JMSrestart)
          {
             log.warn "Worker $id is restarting its JMS producer on a new connection ${this.conn.toString()}"
             try
             {
                if (pSess)    pSess.close()
                if (producer) producer.close()
             }
             catch (Exception e)
             {
                log.warn "Worker $id can't dispose existing JMS producer - ${e.toString()}"
             }
             (pConn, pSess, producer) = j.JMSinitProducer(null, this.conn)
             log.info "Initialized new JMS producer"
             log.info "JMS connection ${pConn.toString()}"
             log.info "JMS session    ${pSess.toString()}"
             log.info "JMS producer   ${producer.toString()}"
             JMSrestart = false
          }
         
          def start = System.nanoTime() / 1000000.0     
          trans++          
          (op, db, query, startrow, numrows) = ParseMessage(message)
          log.info "Transaction T$id#$trans = $op db $db, query: $query, start: $startrow, num: $numrows"
          
          if (!query)
          {
             queryError(message, replyto, pSess, producer, "Missing/invalid parameters")
             continue
          }
   
          def ds = dataSourceIndex.getAt(db.toUpperCase()) 
          if (!ds)           
          { 
             queryError(message, replyto, pSess, producer, "No datasource configured for $db")
             continue
          }

          sql = new Sql(ds)
          if (!sql)
          {
             queryError(message, replyto, pSess, producer, "Can't create a SQL connection for $db")
             continue
          }

          dbresp = "" 
          isupdate = 0
          rs = 0

          switch (op)
          {
          case "CREATE":
          case "INSERT":
          case "UPDATE":
          case "DELETE":
             isupdate = 1
             dbresp = DbUpdate(query, sql, db, log)
             if (dbresp?.contains("<exception>")) 
                errors++
             if (replyto)
             {
                def resp = "<DB_resp>\n" +
                           "<status>OK</status>\n" +
                           "<more>No</more>\n" +
                           dbresp + 
                           "</DB_resp>\n"
                j.JMSsendTo(replyto, pSess, producer, resp)
                rs = resp.size()
             }
             break

        case "CALL":
             this_row = ismore = 0
             (dbresp, this_row, ismore) = DbSelect(query, sql, 0, numrows, db, log)
             if (dbresp?.contains("<exception>"))
                errors++
             if (replyto)
             {
                 def resp = "<DB_resp>\n" +
                            "<status>OK</status>\n" +
                            "<more>No</more>\n" +
                            "<startRow>0</startRow>\n" +
                            dbresp + 
                            "</DB_resp>\n"
                 j.JMSsendTo(replyto, pSess, producer, resp)
                 rs = resp.size()
             }
             break

        case "EXEC": 
             this_row = ismore = 0
             (dbresp, this_row, ismore) = DbExec(query, sql, db, log)
             if (dbresp?.contains("<exception>")) 
                errors++
             if (replyto)
             {
                 def resp = "<DB_resp>\n" +
                            "<status>OK</status>\n" +
                            "<more>No</more>\n" +
                            "<startRow>0</startRow>\n" +
                            dbresp + 
                            "</DB_resp>\n"
                 j.JMSsendTo(replyto, pSess, producer, resp)
                 rs = resp.size()
             }
             break

        case "READ": 
        case "SELECT":  
             // We do it in chunks in case a huge query
             ismore    = 1
             this_row  = startrow
             while (ismore)
             {
                def max_rows = MAX_ROWS
                if (numrows > 0)
                   max_rows = (numrows > MAX_ROWS ? MAX_ROWS : numrows)   

                (dbresp, this_row, ismore) = DbSelect(query, sql, startrow + 1, max_rows, db, log)
                if (dbresp?.contains("<exception>"))
                   errors++
                if (replyto)
                {
                    def resp = "<DB_resp>\n" +
                               "<status>OK</status>\n" +
                               "<more>" + (ismore ? "Yes" : "No") + "</more>\n" +
                               "<startRow>" + startrow.toString() + "</startRow>\n" +
                               dbresp + 
                               "</DB_resp>\n"
                    j.JMSsendTo(replyto, pSess, producer, resp)
                    rs = resp.size()
                 }
                 startrow += max_rows
             }
             break
     
        default:
             queryError(message, replyto, pSess, producer, "Unknown operation $op")
             break
        } // switch op

        if (dbresp?.contains("The server failed to resume the transaction"))
        {
           log.error "DB transaction mismatch - restarting connection and retrying" 
           dataSource.restart() 
           q.put(transact)            // Resubmits the transaction         
        }

        if (isupdate && sql.getUpdateCount() > 0 && ds && !ds.getDefaultAutoCommit()) 
           Commit(sql)

        double elap = ((System.nanoTime() / 1000000.0) - start)
        this.avgresp = (((trans - 1) * avgresp) + elap) / trans     
        sql.close()
        log.info "Transaction T$id#$trans complete - transfer $rs, elapsed ${elap.round(1).toString()} ms, avgresp ${avgresp.round(1).toString()} ms"
        if (rs > 10000000 || elap > 3000.0)
        { 
           log.warn "Note - Long or slow query: size $rs, Time=${elap.round(1).toString()}ms - $query"
           A?.SendAlert("DB Big/Slow Query: Size=$rs, Time=${elap.round(1).toString()}ms - $query") 
        }
        traffic += rs
     } // While
  } // Try 

  catch (InterruptedException IE) 
  { 
     log.info "Worker $id stopping "  
     return
  }

  catch (Exception E) 
  { 
     log.warn "Worker $id Exception: " + E.toString()
     ByteArrayOutputStream out = new ByteArrayOutputStream() 
     E.printStackTrace(new PrintStream(out))
     String str = new String(out.toByteArray())
     log.warn str
     this.errors++
     if (busy && replyto)
     {
        queryError(message, replyto, pSess, producer, "Exception processing query - ${E.toString()}")
     }
  }

 } // Run

 public stats()
 {
    synchronized(this) 
    {
       return [trans, errors, traffic, avgresp] 
    }
 }

 public setJMSConnection(javax.jms.Connection c)
 {
    log.info "Worker $id received new JMS connection ${c.toString()}"
    this.conn = c
    this.JMSrestart = true
 }

 public setAlerter(Object a)
 {
    log.info "Worker $id received new Alerter ${a.toString()}"
    this.A  = a
 }

 // Handle various query errors 

 private void queryError(message, replyto, pSess, producer, errmsg)
 {
    log.error "Transaction Failure: $errmsg"
    log.error "---Input Message---"
    log.error message
    if (replyto) 
    {
       def m = "<DB_resp>\n" +
               "<status>ERROR</status>\n" +
               "<more>No</more>\n" +
               "<message>$errmsg</message>\n" +
               "</DB_resp>\n"
       j.JMSsendTo(replyto, pSess, producer, m) 
    }
    errors++
    A?.SendAlert("Transaction failure: $errmsg")
 }
 
 // Parse incoming XML stream into variables
 
 def ParseMessage(message)
 {
  // Here with a request to run in "message" 
  // It's XML and specific to the service (DB) 
  // <DB>
  //   <operation>      -CRUD or CALL/EXEC/SELECT/INSERT/UPDATE/DELETE- </operation>
  //   <dbname>         -optional DB name- </dbname>
  //   <query>          -SQL statement- </query>
  //   <encodedquery>   -SQL statement URL-encoded- </query-encoded>
  //   <startRow>       -Starting row for SELECT - </startRow>
  //   <numRows>        -Count of rows to return - </numRows>
  // </DB>

     def query = ""
     def op = ""
     def db = ""
     def startrow = 0
     def numrows  = 0

     try
     {
        def xml = new XmlSlurper().parseText(message)
        op    = xml.operation.text()
        db    = xml.dbname.text()
        query = xml.query.text()
        if (xml?.encodedquery?.text())
           query = java.net.URLDecoder.decode(xml?.encodedquery?.text())
        startrow = xml.startrow.text()
        numrows  = xml.numrows.text()     
  
        if (!op || op.equalsIgnoreCase("null"))
           op = "READ"
        if (!db || db.equalsIgnoreCase("null"))
           db = "DEFAULT"
        if (!startrow || startrow.equalsIgnoreCase("null"))
           startrow = "0"
        if (!numrows || numrows.equalsIgnoreCase("null"))
           numrows = "0"
      
        return [op, db, query, startrow.toInteger(), numrows.toInteger()]
     }
     catch (Exception e)
     {
        A.SendAlert("DB Input XML Error <${e?.toString()}>")
        log.warn "Parse Invalid XML input: " + e.toString()
        return [null, null, null, 0, 0]
     }
}

// Save binary data as a file 

def SaveFile(data)
{
 File f = File.createTempFile("rowdata",".bin")
 def fp = f.newOutputStream()
 fp.write(data.bytes, 0, data.size())
 fp.close()
 log.info "Saved binary data to " + f.getAbsolutePath() + " [${data.size()}]" 
}

def Commit(sql)
{
 try
 {
    log.info "Committing database transaction..."
    sql.commit()
 }
 catch (Exception e)
 {
    A.SendAlert("DB COMMIT Exception <${e?.toString()?.minus('com.microsoft.sqlserver.jdbc.SQLServerException: ')}>")
    log.warn "DB: Commit error - " + e.toString()
 }

 return
}

// Format data value based on type
@CompileStatic
def ColData(Object data, String datatype)
{
 switch (datatype.toLowerCase())
 {
  case "varbinary":
    if (!data) 
       return null
    // SaveFile(data)
    return ((String)data)?.bytes?.encodeBase64()?.toString()
    break

  default: 
    return java.net.URLEncoder.encode(data.toString()) 
    break
 }

}

// Execute a SELECT-type DB query

def DbSelect(query, sql, startrow, numrow, db, log)
{
 def resp = ""
 def result = null
 def isExec = false
 def colNames = []
 def colTypes = []
 def colNull = []
 def colAuto = []
 def nextrow = 0
 def is_more  = 0
 def rows = 0 
 
 try 
 {
    if (query?.trim()?.startsWith("exec") || query?.trim()?.startsWith("call"))
        isExec = true

    if (numrow == 0 || isExec)
        result = sql.rows(query)
    {
       meta -> 
           colNames = (1..meta.columnCount).collect
           { meta.getColumnName(it) }
           colTypes = (1..meta.columnCount).collect
           { meta.getColumnTypeName(it) }
           colNull  = (1..meta.columnCount).collect
           { meta.isNullable(it) }
           colAuto  = (1..meta.columnCount).collect
           { meta.isAutoIncrement(it) }
    }
    else
        result = sql.rows(query, startrow, numrow)
    {
       meta -> 
           colNames = (1..meta.columnCount).collect
           { meta.getColumnName(it) }
           colTypes = (1..meta.columnCount).collect
           { meta.getColumnTypeName(it) }
           colNull  = (1..meta.columnCount).collect
           { meta.isNullable(it) }
           colAuto  = (1..meta.columnCount).collect
           { meta.isAutoIncrement(it) }
    }
    def cols = colNames.size()
    
    resp = "<colCount>$cols</colCount>\n"
    for (int i = 0; i < cols; i++)
    {
       if (!colNames[i])
          colNames[i] = "unlabeled_$i"
       resp +="<col-$i name='"   + colNames.getAt(i) + "'" + 
              " type='"          + colTypes.getAt(i) + "'" +
              " nullable='"      + colNull.getAt(i) + "'" +
              " autoincrement='" + colAuto.getAt(i) + "' />\n"  
    }

    if (!result)
    {
       log.warn "SELECT-type query returns no result: $query"
       resp +="<rowCount>0</rowCount>\n"
       is_more = 0
    }
    else
    { 
       rows = result.size()  
       resp +="<rowCount>$rows</rowCount>\n"
       result.each
       {
          def this_resp = "<row>\n" 
          for (int i = 0; i < cols; i++)
               this_resp += "  <" + colNames[i] + ">" +
                      ColData(it[i], colTypes[i]) +
                     "</" + colNames[i] + ">\n"
          resp += this_resp + "</row>\n"
       }  

       nextrow = startrow + result.size()  
       is_more = (numrow > result.size() ? 0 : 1)
       if (isExec || !numrow)
          is_more = 0
       result.clear()
    }

    log.info "Query result set is $rows rows X $cols columns " + (is_more ? "(more pending starting with row $nextrow)" : "(complete)")    
    return [resp, nextrow, is_more]
 }
 catch (SQLException se)
 {
    log.warn "Query <$query> Exception <${se?.toString()?.minus('com.microsoft.sqlserver.jdbc.SQLServerException: ')}>"
    A.SendAlert("DB Query <$query> Exception <${se?.toString()?.minus('com.microsoft.sqlserver.jdbc.SQLServerException: ')}>")
    return ["<exception>SQL Exception: " + se + "</exception>", numrow, 0]
 }
 catch (Exception e)
 {
    log.warn "Query <$query> Exception <${e?.toString()?.minus('com.microsoft.sqlserver.jdbc.SQLServerException: ')}>"
    ByteArrayOutputStream out = new ByteArrayOutputStream()
    e.printStackTrace(new PrintStream(out))
    String estr = new String(out.toByteArray())
    log.warn estr 
    A.SendAlert("DB Query <$query> Exception <${e.toString()}>")
    return ["<exception>Exception: " + e + "</exception>", numrow, 0]
 }

}    

// Execute a stored procedure that doesn't return a result set

def DbExec(query, sql, db, log)
{
 def stats = 0
 def result = null

 try 
 {
    result = sql.execute(query)
    log.info  "Stored procedure exec returns ${result}"    
    return ["<returnValue>$result</returnValue>", 0, 0]
 }
 catch (SQLException se)
 {
    log.warn "Exec <$query> Exception <${se?.toString()?.minus('com.microsoft.sqlserver.jdbc.SQLServerException: ')}>"
    A.SendAlert("DB <$query> Exception <${se?.toString()?.minus('com.microsoft.sqlserver.jdbc.SQLServerException: ')}>")
    return ["<exception>SQL Exception: " + se + "</exception>", 0, 0]
 }
 catch (Exception e)
 {
    log.warn "Exec <$query> Exception <${e?.toString()?.minus('com.microsoft.sqlserver.jdbc.SQLServerException: ')}>"
    A.SendAlert("DB <$query> Exception <${e?.toString()?.minus('com.microsoft.sqlserver.jdbc.SQLServerException: ')}>")
    return ["<exception>Exception: " + e + "</exception>", 0, 0]
 }

}    

// Execute an insert/update/delete query

def DbUpdate(query, sql, db, log)
{
def resp = ""

 try
 {
 if (query?.trim()?.startsWith("exec "))
 {
    // This is an updating stored procedure call 

    sql.execute(query)
    if (sql?.updateCount)
       resp += "<updateCount>" + sql.updateCount + "</updateCount>"
    log.info "Update $query count is ${sql.updateCount}"
    return resp
 }

 def result = sql.executeInsert(query)

 // If this is an INSERT and there's an auto-increment column, 
 // then result contains the new key value for the row just inserted 
     
 if (result)
 {
    resp = "<resultKey>" + result[0][0] + "</resultKey>\n"
    log.info "Update $query result key is ${result[0][0]}"
 }
 resp += "<updateCount>" + sql.updateCount + "</updateCount>\n"
 log.info "Update $query count is ${sql.updateCount}"
 return resp
 }

 catch (SQLException se)
 {
    log.warn "Query <$query> Exception <${se?.toString()?.minus('com.microsoft.sqlserver.jdbc.SQLServerException: ')}>"
    A.SendAlert("DB Query <$query> Exception <${se?.toString()?.minus('com.microsoft.sqlserver.jdbc.SQLServerException: ')}>")
    return "<exception>SQL Exception: " + se + "</exception>"
 }
}

} // End "Consumer" class

def ShowStats(reqs, toterr, msg, ci, di, log)
{
 def mt = (Runtime.getRuntime().totalMemory()/(1024*1024)).toString()?.take(6) + "MB"
 def mu  = ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())/(1024*1024)).toString().take(6) + "MB"
 def mm  = (Runtime.getRuntime().maxMemory()/(1024*1024)).toString().take(6) + "MB"
 OperatingSystemMXBean operatingSystemMXBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()
 RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean()
 int ap = operatingSystemMXBean.getAvailableProcessors()
 def ut = (runtimeMXBean.getUptime()/1000).toString().take(6) + " sec"
 def ct = (operatingSystemMXBean.getProcessCpuTime()/1000000).toString().take(6) + " millisec"
 def now = new Date().format('yyyy-MM-dd@HH:mm:ss')
 def processName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName()
 def pid = processName.split("@")[0]
 def workers = 0
 def dsnum = 0
 def netIO = 0
 def wmsg = ""
 def dmsg = ""
 
 if (ci) workers = ci.size() 
 if (di) dsnum   = di.size() 

 log.info "STATS Pid: $pid Req:$reqs Err:$toterr MemT:$mt MemU:$mu MemM:$mm Uptime:$ut CPU:$ct DS: $dsnum Workers: $workers Core:$ap"

 ci?.each 
 {
   c->
     def (trans, errs, traffic, resp) = c.value.stats()
     log.info "STATS ${c.key} transactions: $trans, errors $errs, net-I/O $traffic" 
     netIO += traffic 
     wmsg += "${c.key}: transactions=$trans errors=$errs net-I/O=$traffic avgresp=${resp.toString().take(6)}\n"  
 }

 di?.each
 {
   d->
     dmsg += "DB-${d.key}: act=${d.value.getNumActive()} idle=${d.value.getNumIdle()} max=${d.value.getMaxTotal()}\n"
 }

 def stats = new File("/var/log/TechTiles/DB.stats")
 if (stats.canWrite())
   stats.newWriter().withWriter 
   { w -> 
    w << "Time: $now\n"    +
         "UPTM: $ut\n"     +
         "Prid: ${runtimeMXBean.getName()}\n" + 
         "Reqs: $reqs\n"   +
         "Errs: $toterr\n" +
         "CPUT: $ct\n"     +
         "Core: $ap\n"     +
         "MTot: $mt\n"     + 
         "MUse: $mu\n"     +
         "MMax: $mm\n"     +
         "CNIO: $netIO\n"  +
         wmsg              +
         dmsg              +
         "Mesg: " + (msg ? "$msg\n" : "*None*\n")
    }

}

// Handle a request to set a new environment variable in our process

def HandleSet(message, log, A)
{
 // Here with a SET request...it's XML like this:
 // <SET>
 //   <name> -environment variable name- </name>
 //   <value> -environment variable value- </value>
 // </SET>a

 def name = ""
 def val = ""
 def resp = ""

 try
 {
    def xml = new XmlSlurper().parseText(message)
    name  = xml.name.text()
    value = xml.value.text()

    if (name && value)
    {
       System.setProperty(name, value)
       log.info "SET property $name to $value"
       return "<status>OK</status>\n<message>$name set to $value</message>"
    }
    
    log.warn "SET failed - missing name or value"
    return "<status>ERROR</status>\n<message>Missing name or value</message>"
 }
 catch (Exception e)
 {
    A.SendAlert("SET exception - ${e?.toString()}")
    log.warn "SET exception - " + e?.toString()
    return null
 }
}

// Initialize a SQL datasource to a database 

def Connect(db, threads, log, A, doAlert)
{
def dbtype  = ""
def dbhost  = "" 
def dbport  = ""
def dbschema= ""
def dbuser  = ""
def dbpass  = ""
def dbdrvr  = ""
def isError = 0
def url = ""
def fname = db
def sep = "/"

try
{
   log.info "Opening datasource $db for $threads way connection pool"

   // Get the connection parameters
 
   if (!db || db.equalsIgnoreCase("default"))
   {
      dbtype  = System.getProperty('JMS_DBTYPE',   DBTYPE)
      dbhost  = System.getProperty('JMS_DBHOST',   DBHOST)
      dbport  = System.getProperty('JMS_DBPORT',   DBPORT)
      dbschema= System.getProperty('JMS_DBSCHEMA', DBSCHEMA)
      dbuser  = System.getProperty('JMS_DBUSER',   DBUSER)
      dbpass  = System.getProperty('JMS_DBPASS',   DBPASS)
      dbdrvr  = System.getProperty('JMS_DBDRIVER', DBDRIVER)
   }
   else
   {
      // For other databases, we have connection information in 
      // a file within our "conf" directory...it can be passed as
      // a relative name or a fully qualified pathname. 
    
      if (!db.contains("/"))
      {
         fname = System.getProperty('FMA_DIR', "/appliance") + "/conf/" + db + ".DB"
         fname.replace("//", "/")
      }
      String config = new File(fname).getText("UTF-8")
      config.eachLine 
      {
         def line = it.trim()
         if (line && !line.startsWith("#") && !line.startsWith("//"))
         {
            def pair = line.split("=")
            if (pair.size() != 2)
            {
               if (doAlert) 
                  A.SendAlert("DB: Connect $db syntax error at <$line> in file $fname")
               else
                  log.warn("Connect $db syntax error at <$line> in file $fname")
            }
            else
            {
               def kwd = pair[0].trim().toLowerCase()
               def val = pair[1].trim()
               switch (kwd)
               {
               case "dbtype":
	         dbtype = val
                 break; 
	       case "dbhost":
	         dbhost = val
                 break; 
	       case "dbport":
	         dbport = val
                 break; 
	       case "dbschema":
	         dbschema = val
                 break; 
	       case "dbuser":
	         dbuser = val
                 break; 
	       case "dbpass":
	         dbpass = val
                 break; 
	       case "dbdrvr":
	         dbdrvr = val
                 break; 
               default: 
                 log.warn "DB configuration $fname has unknown keyword <$kwd>"
                 if (doAlert) A.SendAlert("DB: Connect $db - unknown keyword $kwd in config file $fname")
                 isError = 1
               } // switch...
            } // else...
         } // if line...
      } // eachLine
   } // else...
    
   // Verify that the connection properties look okay

   if (!dbtype   || dbtype?.contains("%DB")   ||
       !dbhost   || dbhost?.contains("%DB")   ||
       !dbport   || dbport?.contains("%DB")   ||
       !dbschema || dbschema?.contains("%DB") ||
       !dbuser   || dbuser?.contains("%DB")   ||
       !dbpass   || dbpass?.contains("%DB")   ||
       !dbdrvr   || isError)
   {
       log.error "Missing/invalid configuration parameters for $db"
       log.error "Found parameters T:$dbtype H:$dbhost:$dbport S:$dbschema U:$dbuser P:$dbpass D:$dbdrvr"
       if (doAlert) A.SendAlert("DB: Connect $db missing parameters - T:$dbtype H:$dbhost:$dbport S:$dbschema U:$dbuser P:$dbpass D:$dbdrvr")
       return null
   }

   // Setup the JDBC URL...slightly different syntax depending on DB type (boo!)

   if (dbtype.contains("sqlserver"))
      sep = ";selectMethod=cursor;packetSize=0;responseBuffering=adaptive;Database="
   // sep = ";encrypt=false;selectMethod=cursor;packetSize=0;responseBuffering=adaptive;Database="
   // sep = ";sslProtocol=TLSv1.2;selectMethod=cursor;packetSize=0;responseBuffering=adaptive;Database="
 
   url = "jdbc:" + dbtype + "://" + dbhost + ":" + dbport + sep + dbschema
   if (dbtype.contains("mysql"))
      url += "?autoReconnect=true"

   def dataSource = new BasicDataSource(driverClassName: dbdrvr, url: url, username: dbuser, password: dbpass)
   if (!dataSource)
   {
      log.warn "Can't connect to $dbtype datasource $db using $url" 
      if (doAlert) A.SendAlert("DB: Connection exception DB $db - ${e.toString()}")
      return null
   }

   def dbfile = db 
   if (dbfile.contains("/")) 
      dbfile = db.substring(db.lastIndexOf("/") + 1) - ".DB" 

   PrintWriter dblog = new PrintWriter("/var/log/TechTiles/DBLOG-$dbfile")
   dataSource.setValidationQuery("SELECT '1'")
   dataSource.setLogWriter(dblog)
   dataSource.setTestWhileIdle(true)
   dataSource.setDefaultAutoCommit(true)
   dataSource.setInitialSize(threads)
   dataSource.setMaxIdle(threads*2)
   dataSource.setMinIdle(1)
   dataSource.setMaxTotal(threads*2)
   dataSource.setTestOnCreate(true) 
   dataSource.setTestOnBorrow(true) 
   dataSource.setTestWhileIdle(true) 
   dataSource.setTimeBetweenEvictionRunsMillis(5000)	
   dataSource.start()
   dataSource.startPoolMaintenance()
    
 //dataSource.setDisconnectionSqlCodes(Collection<String> disconnectionSqlCodes)
    
   log.info "CONNECTED - pool has ${dataSource.getNumActive()}/${dataSource.getNumIdle()}/${dataSource.getMaxTotal()} active/idle/max connections"

   // We force a connection and a query, just to be sure it works 

   def sql = new Sql(dataSource)
   if (!sql)
   {
      log.warn "ERROR - Can't connect to $dbtype database $db using " + url
      dataSource.close()
      return null
   }
   def metaData = dataSource.getConnection()?.getMetaData()
   def r = sql.firstRow("SELECT @@version AS VERSION")
   log.info "Started connection to $dbtype database $db using $url"
   log.info "Database is: ${r.VERSION} [Product ${metaData?.getDatabaseProductName()}, Version ${metaData?.getDatabaseProductVersion()}]" 
   log.info "JDBC driver version: " + metaData.getDriverVersion()
   A.SendAlert("DB: Now connected to $dbtype $db on $dbhost:$dbport schema $dbschema [${metaData?.getDatabaseProductName()} Version ${metaData?.getDatabaseProductVersion()}]")
   sql.close()
   return dataSource
 } // try...

 catch (Exception e)
 {
    log.warn "Exception connecting to $dbtype database $db with $url - ${e.toString()}"
    log.info  e.printStackTrace()
    A.SendAlert("DB: Can't connect to $db with $url - ${e.toString()}")
    return null
 }
}

// Initialize the datasources in a separate thread

def ConnectAll(dataSourceIndex, threads, isShutdown, log, A)
{
 def doAlert = true
 def dataSource = null 
 def numcon 

 Thread.currentThread().setName("ManageDS")
 log.info "Datasource connection management thread starting - ${dataSourceIndex.size()} connections active"

 // Make all the connections we can 

 for (;!isShutdown;)
 {
   // Connect the default DB
   
   numcon = 1 
   if (dataSourceIndex.isEmpty() || !dataSourceIndex.getAt("DEFAULT"))
   {
      dataSource = Connect("Default", threads, log, A, doAlert)
      if (dataSource)
         dataSourceIndex.put("DEFAULT", dataSource)
      else
         log.warn "Default data source not connected"
   } 

   // Now we list all the "*.DB" files and connect them too 

   def dname = System.getProperty('FMA_DIR', "/appliance") + "/conf"
   dname.replace("//", "/")
   def dbFiles = new File(dname)
   dbFiles.eachFile() 
   {
      if (it.toString().toUpperCase().endsWith(".DB"))
      {
         numcon++
         def fn = it.name - ".DB"
         if (!dataSourceIndex.getAt(fn.toUpperCase()))
         {
            log.info "Processing datasource $fn configuration file ${it.toString()}"
            dataSource = Connect(it.toString(), threads, log, A, doAlert)
            if (dataSource)
               dataSourceIndex.put(fn.toUpperCase(), dataSource)
            else
               log.warn "$fn data source not connected"
         } 
      }
   }

   // Now "ping" each connection

   dataSourceIndex.each
   {
     ds->
        try
        { 
           def sql = new Sql(ds.value)
           sql.firstRow("SELECT '1'") 
           sql.close()
        }
        catch (Exception e)
        {
           log.error "Exception validating datasource ${ds.key}"
           log.error e.toString()
           if (sql) sql.close()
           if (ds)  ds.value.restart()
        }
   }

   if (!dataSourceIndex.size())
      log.warn "No datasources connected..."
   else
      if (dataSourceIndex.size() != numcon)
         log.info "Now connected to ${dataSourceIndex.size()} of $numcon defined data sources"
   sleep (60*1000)
   doAlert = false             // Only generate alerts first time through
 }

 log.info "Datasource connection thread ending - ${dataSourceIndex.size()} data sources active"
}

// Main routine starts here... 

// These are the JMS objects - we have a consumer and a producer, 
// and they are active on the same physical network connection. 

javax.jms.Connection      cConn = null
javax.jms.Connection      pConn = null
javax.jms.Session         cSess = null
javax.jms.MessageConsumer consumer = null
javax.jms.Destination     dest = null
javax.jms.Session         pSess = null
javax.jms.MessageProducer producer = null
Object                    j = null
 
// Load some shared methods from a common source file

j = new GroovyClassLoader(getClass().getClassLoader()).parseClass(new File("./include/jms.groovy")).newInstance()
this.class.classLoader.rootLoader.addURL( new URL("file:///appliance/jms/lib/mysql-connector-java-5.1.44-bin.jar"))
this.class.classLoader.rootLoader.addURL( new URL("file:///appliance/jms/lib/mssql-jdbc-8.4.1.jre8.jar"))

def is_shutdown = false
def startup = new Date()
def reqs = 0
def errcount = 0
def toterr = 0
def iserror = 0
def env = System.getenv()
def qname = env['JMS_DB']
def threads = 3
def consumerIndex = [:]
def dataSourceIndex = [:]
def A = null
WorkerMessage trans = new WorkerMessage()

 System.setProperty('FMA_DIR',       env['FMA_DIR']      ? env['FMA_DIR']      : FMADIR)
 System.setProperty('JMS_DBTYPE',    env['JMS_DBTYPE']   ? env['JMS_DBTYPE']   : DBTYPE)
 System.setProperty('JMS_DBHOST',    env['JMS_DBHOST']   ? env['JMS_DBHOST']   : DBHOST)
 System.setProperty('JMS_DBPORT',    env['JMS_DBPORT']   ? env['JMS_DBPORT']   : DBPORT)
 System.setProperty('JMS_DBSCHEMA',  env['JMS_DBSCHEMA'] ? env['JMS_DBSCHEMA'] : DBSCHEMA)
 System.setProperty('JMS_DBUSER',    env['JMS_DBUSER']   ? env['JMS_DBUSER']   : DBUSER)
 System.setProperty('JMS_DBPASS',    env['JMS_DBPASS']   ? env['JMS_DBPASS']   : DBPASS)
 System.setProperty('JMS_DBDRIVER',  env['JMS_DBDRIVER'] ? env['JMS_DBDRIVER'] : DBDRIVER)

 if (env['DB_WORKERS'] && env['DB_WORKERS'].toInteger() > 3) 
    threads = env['DB_WORKERS'].toInteger() 
 
 Logger log = Logger.getInstance(getClass())
 log.level = Level.INFO
 log.info "---DB SERVICE STARTUP---"
 log.info "   Tenant ${env['TENANT']} Host: ${java.net.InetAddress.getLocalHost().getHostName()} Groovy: ${GroovySystem.version}, JVM: ${System.getProperty("java.version")} " 
 log.info "   System: ${System.getProperty('os.name')} ${System.getProperty('os.version')} on ${System.getProperty('os.arch')}"
 log.info "   QNAME: $qname Workers: $threads Default DB Type: ${env['JMS_DBTYPE']}, DB Host: ${env['JMS_DBHOST']}, DB Schema: ${env['JMS_DBSCHEMA']}"   
 
 // Initialize the JMS connection 

 if (!qname)
 {
   log.error "ERROR: No queue name for this tenant and service [JMS_DB]"
   System.exit(4)
 }
 
 for (;;)
 { 
    (cConn, cSess, consumer, dest) = j.JMSinitConsumer(qname)
    (pConn, pSess, producer) = j.JMSinitProducer(null, cConn)
    if (!consumer || !producer)
       log.error "ERROR: ERROR - Can't create JMS connection on $qname"
    else
       break
    sleep 30*1000
 }

 A = new Alerter(j, log, cConn)
 A.SendAlert("DB Service Starting for Tenant ${env['TENANT']} on ${java.net.InetAddress.getLocalHost().getHostName()} with Groovy: ${GroovySystem.version}, JVM: ${System.getProperty("java.version")}") 
 ShowStats(0, 0, "Initializing", null, null, log)

 // Asynchronously initialize the datasources

 def connThread = Thread.start { ConnectAll(dataSourceIndex, threads, is_shutdown, log, A) }
 
 log.info "Initializing worker thread pool for $threads concurrent tasks"
 BlockingQueue q = new LinkedBlockingQueue<String>()
 ExecutorService pool = Executors.newFixedThreadPool(threads)
 (1..threads).each 
 { 
    log.info "Starting worker thread $it"
    pool.execute(new Consumer(it, this, q, dataSourceIndex, consumerIndex, j, cConn, log, A)) 
 }

 // Wait here for datasource(s) to open 

 sleep 5000
 log.info "DB Service Now READY on Queue $qname with ${consumerIndex.size()} workers and ${dataSourceIndex.size()} active data sources "
 A.SendAlert "DB Service Now READY on Queue $qname with ${consumerIndex.size()} workers and ${dataSourceIndex.size()} active data sources "
 ShowStats(0, 0, "Initialization Complete", null, dataSourceIndex, log)

 // This is the main processing loop - we wait here processing messages
 // as they are delivered. Right now, this is single threaded, but it's 
 // possible to run many copies of this process, each servicing the same
 // JMS input queue. 

 while (!is_shutdown)
 {
  try 
  {
     def (message, replyto, msgtime) = j.JMSrecvWithReplyTo(consumer, 60000*5)
     if (!message)  // This is a timeout or a JMS error 
     {
        if (consumer.session.closed || producer.session.closed || cSess.closed || pSess.closed)
        {
           // Something happened to the network or to the broker
           log.warn "JMS Connection closed...reconnecting"
           try
           {
              j.JMSterm(cConn, cSess, consumer)
              pSess.close()
              pConn.close()
           }
           catch (Exception e)
           {
              log.warn "JMS Session cleanup incomplete"
           }
           for (;;)
           {
              (cConn, cSess, consumer, dest) = j.JMSinitConsumer(qname)
              if (!cConn)
              {
                  log.warn "Reconnect failed - retrying $qname"
                  sleep(30*1000)
                  continue
              }
              (pConn, pSess, producer) = j.JMSinitProducer(null, cConn)
              log.info "JMS reconnect succeeded for $qname"
              startup = new Date()
              A.setConnection(cConn)
              consumerIndex.each { it.value.setJMSConnection(cConn) }
              break
           }
        }

        System.gc()
        ShowStats(reqs, toterr, "Wait for work", consumerIndex, dataSourceIndex, log)
        log.info "Now waiting for work..."
        continue 
     }

     reqs++
    
     // A few requests are handled here in the main thread
 
     if (message.contains("<stop>"))     // Shutdown the service
     {
        if (startup > new Date(msgtime))
        {
           def tstr = new Date(msgtime).format('yyyy-MM-dd@HH:mm:ss')
           log.warn "Stale shutdown message ignored from $tstr"
           continue
        }
        log.info "***Shutdown request received***"
        is_shutdown = 1
        if (replyto)
        {  
           def errmsg = "<DB_resp>\n" +
               "<status>SHUTDOWN</status>\n" +
               "<more>No</more>\n" +
               "<message>The database service is shutting down</message>" +
               "</DB_resp>\n"
           j.JMSsendTo(replyto, pSess, producer, errmsg)
        }
        break
     } 
     
     if (message.contains("<set>"))      // Change a system property
     {
       def sm = HandleSet(message, log, A)
       if (replyto)
       {
          def setmsg = "<set_resp>\n" +
              "<status>Okay at " + System.currentTimeMillis() + "</status>\n" +
               "<more>No</more>\n" +
              sm + "\n" +
              "</set_resp>\n"
          j.JMSsendTo(replyto, pSess, producer, setmsg)
       }
       continue
     }

     if (message.contains("<reset>"))    // Restart all the datasources 
     {
        log.info "RESET request received"
        dataSourceIndex.each
        {
           log.info "Resetting datasource ${it.key}"
           it.value.restart()
        }
        if (replyto)
        {  
           def errmsg = "<reset_resp>\n" +
               "<status>Okay at " + System.currentTimeMillis() + "</status>\n" +
               "<transactions>" + reqs.toString() + "</transactions>\n" +
               "<more>No</more>\n" +
               "</reset_resp>\n"
           j.JMSsendTo(replyto, pSess, producer, errmsg)
           ShowStats(reqs, toterr, "Reset connection", consumerIndex, dataSourceIndex, log)
        }
        continue
     } 
     
     if (message.contains("<ping>"))     // Ping the service
     {
        log.info "PING request received"
        ShowStats(reqs, toterr, "Ping command", consumerIndex, dataSourceIndex, log)
        def pr = ""
        def pf = new File("/var/log/TechTiles/DB.stats")
        if (pf.exists())
        {
           pf.getText()?.eachLine
           { l ->
              def parts = l.split(":") 
              pr += "<${parts[0].trim()}>"
              if (parts.size() > 1)
                 pr += l.substring(l.indexOf(":")+ 1).trim()
              pr += "</${parts[0].trim()}>\n" 
           }
        }
        if (replyto)
        {  
           def errmsg = "<ping_resp>\n" +
               "<status>Okay at " + System.currentTimeMillis() + "</status>\n" +
               "<transactions>" + reqs.toString() + "</transactions>\n" +
               pr  +
               "<more>No</more>\n" +
               "</ping_resp>\n"
           j.JMSsendTo(replyto, pSess, producer, errmsg)
        }
        continue
     }

     // If none of the above, we queue the message to a worker thread 
     
     trans.message = message 
     trans.replyto = replyto
     q.put(trans)

     // If we get swamped, we'll slow down our JMS reads so as not to bring in more than our consumers can handle 

     while (q.size() > (threads * 10))
     {
          log.warn "Message backlog exists - currently " + q.size() + " queued entries for $threads workers"
          sleep 200
     } 

  } // try
  catch (Exception e)
  {  
     log.error "Exception in main thread - " + e.toString()
     log.error e.printStackTrace()
     continue
  }
} // while !shutdown

// Here when a shutdown event occurs

try
{
   log.info "Service stopping..."
   
   // First we stop the worker threads

   connThread.interrupt() 

   trans.message = "!!!STOP!!!"
   (1..threads).each
   {
       log.info "Writing shutdown message to consumer $it"
       q.put(trans) 
   }
   pool.shutdown()
   sleep 1000 
   int retries = 10
   while (q.size() > 0 && retries--)
   { 
    sleep 500
    log.info "Waiting for consumer shutdown - ${q.size()} to go"
   }

   try 
   {
      if (!pool.awaitTermination(10, TimeUnit.SECONDS)) 
      {
         pool.shutdownNow()
         if (!pool.awaitTermination(10, TimeUnit.SECONDS))
            log.warn "Pool did not terminate"
      }
   } catch (InterruptedException E) 
   {
       pool.shutdownNow();
   }

   A.SendAlert("DB: *Service Now Shutdown*") 
   j.JMSterm(cConn, cSess, consumer)
   pSess.close()
   pConn.close()

   dataSourceIndex.each
   {
      if (!it.value.getConnection().isClosed())
      {
         log.info "Closing datasource for $it.key" 
         it.value.close()
      }
   }

   def pidfile = new File("${env['FMA_DIR']}/data/DB.pid")
   if (pidfile.exists() && pidfile.canWrite())
      pidfile.delete()

   ShowStats(reqs, toterr, "Shutdown", null, null, log)
   log.info "Service now stopped"
   System.exit(0)
}
catch (Exception e)
{
   log.warn  "Exception processing shutdown - cleanup may be incomplete"
   log.warn e
   log.warn e.printStackTrace()
   System.exit(0)
}

// END of DB.GROOVY
