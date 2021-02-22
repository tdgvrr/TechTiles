// 
// Alerts.groovy - Network Alert service 
//
// History: 
//    
//    20-Feb-2021: New 
//    01-May-2020: Add management instrumentation for Zabbix 
//    11-Nov-2020: Support isolated transactions 

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
import groovy.*
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.lang.management.* 


// Parse incoming XML stream into variables

def ParseMessage(message)
{
  // Here with a request to run in "message" 
  // It's XML and specific to the service (DB) 
  // <Alert>
  //   <timestamp>      - Time of the event
  //   <message>        - Message text (URL encoded)
  //   <source>         - Original hostname
  //   <tenant>         - Five-digit tenant ID
  // </Alert>

     def tod    = null
     def alert  = null
     def src    = null
     def tenant = null

     try
     {
        def xml= new XmlSlurper().parseText(message)
        if (!xml)
           return [null, null, null, null]
        tod = xml.timestamp?.text()?.trim()
        if (!tod) 
           tod = new Date().format("YYYY-MM-DD hh:mm:ss")
        src    = xml.source?.text()?.trim()
        alert  = java.net.URLDecoder.decode(xml?.message?.text()).replaceAll("'", "''")
        tenant = xml.tenant?.text()
  
        return [tod, src, alert, tenant]
     }
     catch (Exception e)
     {
        println "Alert: ERROR - Invalid XML input: " + e.toString()
        return [null, null, null, null]
     }
}

// Initialize a SQL connection to the default database 

def Connect(env)
{
def url = ""

 try
 {
    // Defaults are set as environment variables
    
    def dbhost  = env['TTDB']
    def dbport  = env['TTDBPORT']
    def dbuser  = env['TTDBUSER']
    def dbpass  = env['TTDBPASS']
 
    url = "jdbc:mysql://" + dbhost + ":" + dbport + "/Management?autoReconnect=true"
    Sql sql = Sql.newInstance(url, dbuser, dbpass, "com.mysql.jdbc.Driver")
    if (!sql)
    {
       println "Alert: ERROR -Can't connect to database using " + url
       return null
    }
    println "Alert: Connected to database using " + url 
    DatabaseMetaData metaData = sql.getConnection().getMetaData()
    println "Alert: JDBC driver version: " + metaData.getDriverVersion()
    println "Alert: Database is: " + metaData.getDatabaseProductName() + " " + metaData.getDatabaseProductVersion() 
    return sql
 }
 catch (Exception e)
 {
    println   "Alert: Can't connect using $url - ${e.toString()}"
    return null
 }
}

def Prune(sql, days)
{
 try
 {
    if (sql && sql.getConnection() && !sql.getConnection().isClosed())
    {
       def r = sql.firstRow("SELECT COUNT(*) AS Count FROM Management.Alerts")
       println "Alert: Prune DB - initial alert count is ${r.Count}" 
       sql.execute("DELETE from Management.Alerts where Timestamp < now() - interval $days DAY")
       def r2= sql.firstRow("SELECT COUNT(*) AS Count FROM Management.Alerts")
       println "Alert: Prune DB deleted ${r.Count - r2.Count} rows - alert count now ${r.Count}" 
    }
 }
 catch (Exception e)
 {
    println "Alert: Prune exception - ${e.toString()}"
    return
 }
} 

// Main routine starts here... 
     
// Load some shared methods from a common source file

def j = new GroovyClassLoader(getClass().getClassLoader()).parseClass(new File("./include/jms.groovy")).newInstance()

def startup = new Date()
def is_shutdown = false
def reqs = 0
def yesterday = "0"
def env = System.getenv()
def qname = "FM.ALERT.SERVICE"
def row = null

def (cConn, cSess, consumer, dest) = j.JMSinitConsumer(qname)
if (!cConn)
{
   println "Alert: ERROR - Can't create JMS connection using $qname"
   System.exit(4)
}

def sys = "${System.getProperty('os.name')} ${System.getProperty('os.version')} on ${System.getProperty('os.arch')}"
println "Alert: System $sys with Groovy ${GroovySystem.version} and Java ${System.getProperty('java.version')}"

Sql sql = Connect(env)
if (!sql)
{
   println   "Alert: No database connection - exiting"
   System.exit(4) 
}

println "Alert: System DB Service Now READY on Queue " + qname

// This is the main processing loop - process messages as they come in

try
{
  def hostname = java.net.InetAddress.getLocalHost().getHostName()
  sql.executeInsert("INSERT INTO Management.Alerts (Timestamp, Alert, Source, Tenant) " +	
                    " VALUES (NOW(), 'AlertHost Startup on $hostname', '$hostname' , 'SYS')")

  while (!is_shutdown)
  {
     def (message, replyto, msgtime) = j.JMSrecvWithReplyTo(consumer, 60000*5)
     if (!message) 
     {
        if (consumer.session.closed || cSess.closed)
        {
           // This means something happened to the network or to the broker
           println "Alert: WARNING - JMS Connection closed...reconnecting"
           try { j.JMSterm(cConn, cSess, consumer) }
           catch (Exception e) { println "Alert: Session cleanup incomplete" }
           
           for (;;)
           {
              (cConn, cSess, consumer, dest) = j.JMSinitConsumer(qname)
              if (!cConn)
              {
                  println "Alert: Reconnect failed/consumer - retrying $qname"
                  sleep(30*1000)
                  continue
              }
              println "Alert: JMS reconnect - consumer connected for $qname"
              startup = new Date()
              break
           }
        }

        System.gc()
        if (sql && sql.getConnection() && !sql.getConnection().isClosed())
           sql.firstRow("Select 1")  
        def now = new Date()
        def today = now.format('dd')
        if (today > yesterday)
        {
           Prune(sql, 30)
           yesterday = today
        }
        def tod = now.format('MMM dd HH:mm:ss.SSS')
        println "Alert: Waiting for a request at $tod ..."
        continue 
     }

     reqs++

     // This is a special request to stop the service 
     
     if (message.contains("<stop>"))
     {
        if (startup > new Date(msgtime))
        {
           def tstr = new Date(msgtime).format('yyyy-MM-dd@HH:mm:ss')
           println "Alert: Stale shutdown message ignored from $tstr"
           continue
        }
        println "Alert: Shutdown request received"
        is_shutdown = 1
        break
     }
 
     if (!sql || !sql.getConnection() || sql.getConnection().isClosed())
     {
        println "Alert: Connection closed - reopening" 
        if (sql) sql.close()
        sql = Connect(env)
        println "Alert: DB Connection READY - reconnected"
     }
     
     def (tod, src, alert, tenant) = ParseMessage(message)
     println "Alert: $tod From: $src For: $tenant Alert: $alert"
     if (!alert)
     {
        println "Alert: ERROR - Missing parameters"
        println message
        continue
     }
 
     def query = "INSERT INTO Management.Alerts " + 
                 "(Timestamp, Alert, Source, Tenant) " +	
                 " VALUES ('" + tod + "', '" + alert + "', '" + src + "', '" + tenant + "')"
     def result = sql.executeInsert(query)
     if (result)
        row = result[0][0] 
  } // while !shutdown/

  j.JMSterm(cConn, cSess, consumer)

  println "Alert: Service now stopped after $reqs records"
  System.exit(0)
}

catch (Exception e)
{
   println "***DB: Exception processing shutdown - cleanup may be incomplete"
   println e
   println e.printStackTrace()
   System.exit(0)
}
