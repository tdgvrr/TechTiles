import groovy.sql.Sql
import java.sql.Connection
import java.sql.Statement
import javax.sql.DataSource
import java.security.*
import java.text.*
import groovy.text.*
import java.util.regex.*
import java.util.*
import groovy.*
import groovy.io.FileType
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.lang.management.* 

import static Constants.*

def ShowStats(reqs, errs)
{
 def now = new Date().format('yyyy-MM-dd@HH:mm:ss')
 def mt = (Runtime.getRuntime().totalMemory()/(1024*1024)).toString() + "MB"
 def mu  = ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())/(1024*1024)).toString() + "MB"
 def mm  = (Runtime.getRuntime().maxMemory()/(1024*1024)).toString().take(6) + "MB"
 OperatingSystemMXBean operatingSystemMXBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
 RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
 int ap = operatingSystemMXBean.getAvailableProcessors();
 def ut = (runtimeMXBean.getUptime()/1000).toString() + " seconds "
 def ct = (operatingSystemMXBean.getProcessCpuTime()/1000000).toString() + " millisec"
 def processName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName()
 def pid = processName.split("@")[0]
 
 println "STAT: $now [$pid] Reqs: $reqs, Errs: $errs, MemTot:$mt MemUsed:$mu MemMax:$mm Uptime:$ut CPU:$ct [$ap]"
}

// Parse incoming XML stream into variables

def ParseMessage(message)
{
  // Here with a request to run in "message" 
  // It's XML and specific to the service (control) 
  // <control>
  //   <command> -command- </password>
  // </control>

     def command = ""

     try
     {
        def xml = new XmlSlurper().parseText(message)
        command = xml.command.text()
        return command
     }
     catch (Exception e)
     {
        println "ERROR: Invalid Command XML input"
        return null
     }
}


def RunCommand(command)
{
 // First, run whatever command the user passed to us
 
 def resp = ""
 def env = System.getenv()
 def dir = env['FMA_DIR']

 if (command.startsWith("restart "))
 {
    // Parameter is name of service(s) to restart 

    def svcs = command.split(" ")
    def s = " "
    svcs.each { s += it + " " }
    def rcmd = dir + "/bin/JMSrestart" + s 
    println "CONTROL: Restart command - $rcmd"
    def out = new StringBuilder()
    def err = new StringBuilder()
    def proc = rcmd.execute()
    proc.waitForProcessOutput(out, err)
    resp += "<status>Restart RC " + proc.exitValue() + "</status>\n"
    if (err)
       resp += "<STDERR>" + err + "</STDERR>"
    if (out)
       resp += "<STDOUT>" + out + "</STDOUT>"
    return resp
 }

 if (command.startsWith("GetIP"))
 {
    def icmd= env['FMA_DIR'] + "/bin/GetVPNip"
    println "CONTROL: GetIP - $icmd"
    def out = new StringBuilder()
    def err = new StringBuilder()
    def proc = icmd.execute()
    proc.waitForProcessOutput(out, err)
    resp += "<status>GetIP RC " + proc.exitValue() + "</status>\n"
    if (err)
       resp += "<STDERR>" + err + "</STDERR>"
    if (out)
       resp += "<STDOUT>" + out + "</STDOUT>"
    return resp
 } 

 try
 {
    def out = new StringBuilder()
    def err = new StringBuilder()
    def proc = command.execute()
    proc.waitForProcessOutput(out, err)
    resp += "<status>Command RC " + proc.exitValue() + "</status>\n"
    if (err)
       resp += "<STDERR>" + java.net.URLEncoder.encode(err.toString()) + "</STDERR>"
    if (out)
       resp += "<STDOUT>" + java.net.URLEncoder.encode(out.toString()) + "</STDOUT>"
    return resp
   
 }
 catch (Exception e)
 {
    println "CONTROL: WARNING - Exception during command processing - " + e.toString()
    return "<status>Exception: " + e.toString() + "</status>"
 }
}

def SetConfig(message)
{
 try
 {
  def te = new SimpleTemplateEngine()
  def env = System.getenv()
  def Tenant = env['TENANT']
  def Atype  = env['AUTH']
  def Auser  = env['AUTH_USER']
  def Apass  = env['AUTH_PASS']
  def Ahost  = env['AUTH_HOST']
  def Adn    = env['AUTH_DN']
  def Adomain= env['AUTH_DOMAIN'] 
  def Adefdmn= env['AUTH_DEFDMN']
  def xml = new XmlSlurper().parseText(message)
  
  if (!xml)
     return "<message>ERROR: Can't parse XML input</message>"

  if (xml.AuthType?.text().contains("AD") || xml.AuthType?.text().contains("ActiveDirectory"))
     Atype = "ActiveDirectory"

  if (xml.AuthUser?.text())
     Auser = xml.AuthUser?.text()

  if (xml.AuthPass?.text() && !xml.AuthPass?.text().startsWith("***"))
     Apass = xml.AuthPass?.text()

  xml.DatabaseConnection.each
  {
   db ->
   def DBtype 
   def DBhost 
   def DBport  
   def DBschema 
   def DBuser   
   def DBdriver 
   def DBpass   
   def fn = "../conf/DB.template"
   def dbf = ""

   // Since we regenerate the full config files, we need all the 
   // current values in order to update the file correctly. 

   if (!db.DBName || db.DBName.text().equalsIgnoreCase("Default"))
   {
      fn = "../conf/sysvars.template"
      DBtype   = (db.DBType   ? db.DBType.text()   : env['JMS_DBTYPE']) 
      DBhost   = (db.DBHost   ? db.DBHost.text()   : env['JMS_DBHOST']) 
      DBport   = (db.DBPort   ? db.DBPort.text()   : env['JMS_DBPORT']) 
      DBschema = (db.DBschema ? db.DBSchema.text() : env['JMS_DBSCHEMA'])
      DBuser   = (db.DBUser   ? db.DBUser.text()   : env['JMS_DBUSER'])
      DBdriver = (db.DBDrvr   ? db.DBDrvr.text()   : env['JMS_DBDRIVER'])
      DBpass   = (db.DBPass   ? db.DBPass.text()   : env['JMS_DBPASS'])
      if (DBpass.startsWith("***")) 
         DBpass = env['JMS_DBPASS']
   }
   else
   {
      // First we need the existing config data in file <DBNAME>.DB
      new File("../conf").eachFile() 
      { 
         file->   
         if (file.getName().equalsIgnoreCase(db.DBName.text() + ".DB"))
            dbf = "../conf/" + file.getName()
      }
      if (!dbf)
      {
         println "CONTROL: SetConfig - No valid DB file found for ${db.DBName.text()}"
         return "<message>Invalid DBNAME ${db.DBName}</message>"
      }
      
      new File(dbf).readLines().each
      {
         line->
         if (!line?.trim()?.startsWith("#"))
         {
            def parts = line.split("=")
            def kwd = parts[0].trim().toUpperCase()
            def val = parts[1].trim()

            switch (kwd)
            {
               case "DBTYPE": 
                  DBtype = val
                  break
               case "DBHOST":
                  DBhost = val
                  break
               case "DBPORT":
                  DBport = val
                  break
               case "DBSCHEMA":
                  DBschema = val
                  break
               case "DBUSER":
                  DBuser = val
                  break
               case "DBPASS":
                  DBpass = val
                  break
               case "DBDRVR": 
                  DBdriver = val
                  break
               default: 
                  println "CONTROL: SetConfig - $dbf Invalid keyword - $kwd = $val"
            }
         }
      }
   }

   def td = new File(fn).text

   def vars = [ tenant:     Tenant, 
                authtype:   Atype, 
                authuser:   Auser, 
                authpass:   Apass, 
                authhost:   Ahost,
                authdn:     Adn,
                authdomain: Adomain,
                authdefdmn: Adefdmn,
                dbtype:     DBtype, 
                dbhost:     DBhost, 
                dbport:     DBport,
                dbschema:   DBschema,
                dbuser:     DBuser, 
                dbpass:     DBpass, 
                dbdriver:   DBdriver ]
   
   def content = te.createTemplate(td).make(vars).toString()

   println "CONTROL: SetConfig - New value from $fn"
   println content
 
   if (fn.contains("sysvars"))
      new File(fn.replace(".template",".NEW")).write(content)
   else
      new File(dbf.replace(".DB",".NEW")).write(content)
 } // each...
 return "<message>Okay</message>"
} //try...
 catch (Exception e)
 {
   println "CONTROL: WARNING - SetConfig exception: " + e
   return "<message>ERROR: Can't process SetConfig</message>"
 }
}

def GetConfig()
{
 String ID   = new File("../conf/SystemID").getText("UTF-8")?.trim()
 String Type = new File("../conf/SystemType").getText("UTF-8")?.trim()
 def env = System.getenv()
 def Atype = env['AUTH']
 def AUser = env['AUTH_USER']

 def dbs = []

 // Get all the .DB files 

 def dir = new File("../conf")
 dir.eachFileRecurse (FileType.FILES) 
 { file ->
     if (file.getName().endsWith(".db") || file.getName().endsWith("DB"))  
        dbs << file
 }

 def resp = "<Serial>"     + ID       + "</Serial>\n"   + 
            "<Type>"       + Type     + "</Type>\n"     +
            "<AuthType>"   + Atype    + "</AuthType>\n" +  
            "<AuthUser>"   + AUser    + "</AuthUser>\n" +  
            "<AuthPass>"   + "******" + "</AuthPass>\n" +   
            "<DatabaseConnection>\n" +
            " <DBName>DEFAULT</DBName>\n"               +
            " <DBType>"   + env['JMS_DBTYPE']   + "</DBType>\n"   +
            " <DBHost>"   + env['JMS_DBHOST']   + "</DBHost>\n"   +
            " <DBPort>"   + env['JMS_DBPORT']   + "</DBPort>\n"   +
            " <DBSchema>" + env['JMS_DBSCHEMA'] + "</DBSchema>\n" +
            " <DBUser>"   + env['JMS_DBUSER']   + "</DBUser>\n"   +
            " <DBPass>"   + "********"          + "</DBPass>\n"   +
            " <DBDrvr>"   + env['JMS_DBDRIVER'] + "</DBDrvr>\n"   +
            "</DatabaseConnection>\n"
 
 dbs.each 
 {
   def dbname   = it.getName().split("\\.")[0]
   def dbtype   = "sqlserver"
   def dbhost   = ""
   def dbport   = ""
   def dbschema = ""
   def dbuser   = ""
   def dbpass   = ""
   def dbdrvr   = "com.microsoft.sqlserver.jdbc.SQLServerDriver"
   
   def db = new File(it.path).eachLine()
   { l ->
      if (l && (l = l.trim()) && !l.startsWith("#"))
      {
         def parts = l.split("=")
         if (parts.length == 2)
         {
         switch (parts[0].trim().toLowerCase())
         {
          case "dbtype":
               dbtype = parts[1].trim()
               break

          case "dbhost":
               dbhost = parts[1].trim()
               break

          case "dbport":
               dbport = parts[1].trim()
               break
          
          case "dbschema":
               dbschema = parts[1].trim()
               break

          case "dbuser":
               dbuser = parts[1].trim()
               break

          case "dbpass":
               dbpass = parts[1].trim()
               break

          case "dbdrvr":
               dbdrvr = parts[1].trim()
               break

          default:
               println "CONTROL: ERROR - Unknown keyword in DB file: $l"
         } // switch

         } // if...
      } // if ...
   } // eachLine...
 
   // Show the database connection
   resp += "<DatabaseConnection>\n"   + 
           " <DBName>"   + dbname     + "</DBName>\n"   +  
           " <DBType>"   + dbtype     + "</DBType>\n"   +  
           " <DBHost>"   + dbhost     + "</DBHost>\n"   +  
           " <DBPort>"   + dbport     + "</DBPort>\n"   +  
           " <DBSchema>" + dbschema   + "</DBSchema>\n" +  
           " <DBUser>"   + dbuser     + "</DBUser>\n"   +  
           " <DBPass>"   + "********" + "</DBPass>\n"   +
           " <DBDrvr>"   + dbdrvr     + "</DBDrvr>\n"   +  
           "</DatabaseConnection>\n"
 }
  
 return resp

} 

def GetLogs(message)
{
 // Here to fetch one or more log files.
 // "message" is XML describing the request: 
 // <GetLogs>
 //   <log> -DB | Login | Logoff | Control- </log>
 //   <user> -username- </user>
 //   <pass> -password- </password>
 //   <lines> - lines to return- </lines>
 // </GetLogs>

 def env = System.getenv()
 def logfile = ""
 def u = ""
 def p = ""
 def l = 10
 def result = "<status>ERROR: Missing or invalid request parameters</status>\n"
     
 try
 {
    def xml = new XmlSlurper().parseText(message)
    logfile = xml.log.text()
    u = xml.user.text()
    p = xml.pass.text()
    l = xml.lines.text()
 }
 catch (Exception e)
 {
    println "CONTROL: ERROR - Invalid GetLogs XML input"
    return result 
 }

 // Make sure the user is legit

 if (!u || !p)
    return result
 
 if (!u.equals(env['AUTH_USER']))
    return "<status>ERROR: Invalid User $u</status>\n"
     
 if (!p.equals(env['AUTH_PASS']))
    return "<status>ERROR: Invalid admin password</status>\n"

 // Now, figure out which logile(s) to process

 if (!logfile || logfile.equalsIgnoreCase("ALL"))
    logfile = "Login Logoff Control DB"
 
 return RunCommand("../bin/GetLogs -n $l $logfile")
}

// Main routine starts here... 
     
// Load some shared methods from a common source file

def j = new GroovyClassLoader(getClass().getClassLoader()).parseClass(new File("./include/jms.groovy")).newInstance()
def startup = new Date()
def is_shutdown = false
def reqs = 0
def errcount = 0
def env = System.getenv()
def qname = env['JMS_CONTROL']
if (!qname)
{
   println "ERROR: No queue name for this tenant and service [JMS_CONTROL]"
   System.exit(4)
} 

def (pConn, pSess, producer) = j.JMSinitProducer(null)
def (cConn, cSess, consumer, dest) = j.JMSinitConsumer(qname)
if (!cConn)
{
   println "ERROR: Can't create JMS connection using $qname"
   System.exit(4)
}

println "System CONTROL Service Now READY on Queue " + qname
def sys = "${System.getProperty('os.name')} ${System.getProperty('os.version')} on ${System.getProperty('os.arch')}"
println "CONTROL: System $sys with Groovy ${GroovySystem.version} and Java ${System.getProperty('java.version')}"

// This is the main processing loop - we wait here processing messages
// as they are delivered. Right now, this is single threaded, but it's 
// possible to run many copies of this process, each servicing the same
// JMS input queue. 

while (!is_shutdown)
{
  try 
  {
     def (message, replyto, msgtime) = j.JMSrecvWithReplyTo(consumer, 60000*5)
     if (!message) 
     {
        // Anything asynchronous can happen here - there's nothing to do

        if (consumer.session.closed || cSess.closed)
        {
           // This means something happened to the network or to the broker
           println "CONTROL: WARNING - JMS Connection closed...reconnecting"

           try
           {
              j.JMSterm(cConn, cSess, consumer)
              pSess.close()
              pConn.close()
           }
           catch (Exception e)
           {
              println "CONTROL: Session cleanup inconplete"
           }
           def connected = 0
           while (!connected)
           {
              (pConn, pSess, producer) = j.JMSinitProducer(null)
              if (!pConn)
              {
                  println "CONTROL: Reconnect failed/producer - retrying $qname"
                  sleep(30*1000)
                  continue
              }
              println "CONTROL: JMS reconnect - producer connected for $qname"
              (cConn, cSess, consumer, dest) = j.JMSinitConsumer(qname)
              if (!cConn)
              {
                  println "CONTROL: Reconnect failed/consumer - retrying $qname"
                  sleep(30*1000)
                  continue
              }
              println "CONTROL: JMS reconnect - consumer connected for $qname"
              println "CONTROL: JMS reconnect succeeded for $qname"
              startup = new Date()
              connected = 1
              break
           }
        }
        System.gc()
        ShowStats(reqs, errcount)
        errcount = 0
        println "CONTROL: Waiting for a request..."
        continue 
     }
 
     def start = System.nanoTime() / 1000000
     reqs++

     // This is a special request to stop the service 
     
     if (message.contains("<stop>"))
     {
        if (startup > new Date(msgtime))
        {
           def tstr = new Date(msgtime).format('yyyy-MM-dd@HH:mm:ss')
           println "CONTROL: Stale shutdown message ignored from $tstr"
           continue
        }

        println "CONTROL: Shutdown request received"
        is_shutdown = 1
        if (replyto)
        {  
           def errmsg = "<control_resp>\n" +
               "<status>SHUTDOWN</status>\n" +
               "<session>*NONE*</session>\n" +
               "<message>The service is shutting down</message>\n" +
               "<role>*NONE*</role>\n" +
               "</control_resp>\n"
           j.JMSsendTo(replyto, pSess, producer, errmsg)
        }
        break
     } 

     // Check for a "Ping" request 
     
     if (message.contains("<ping>"))
     {
        println "Control: PING request received"
        if (replyto)
        {  
           def errmsg = "<ping_resp>\n" +
               "<status>Okay at " + System.currentTimeMillis() + "</status>\n" +
               "<transactions>" + reqs.toString() + "</transactions>\n" +
               "</ping_resp>\n"
           j.JMSsendTo(replyto, pSess, producer, errmsg)
        }
        continue
     }
    
     // Handle a "SetConfig" request 
     
     if (message.contains("<SetConfig>"))
     {
        println "CONTROL: SetConfig request received"
        if (replyto)
        {  
           def errmsg = "<control_resp>\n" +
               SetConfig(message) +
               "</control_resp>\n"
           j.JMSsendTo(replyto, pSess, producer, errmsg)
        }
        continue
     }

     // Handle a "GetConfig" request 
     
     if (message.contains("<GetConfig>"))
     {
        println "CONTROL: GetConfig request received"
        if (replyto)
        {  
           def errmsg = "<control_resp>\n" +
               GetConfig() +
               "</control_resp>\n"
           j.JMSsendTo(replyto, pSess, producer, errmsg)
        }
        continue
     }

     // Handle a "GetLogs" request 
     
     if (message.contains("<GetLogs>"))
     {
        println "CONTROL: GetLogs request received"
        if (replyto)
        {  
           def errmsg = "<control_resp>\n" +
               GetLogs(message) +
               "</control_resp>\n"
           j.JMSsendTo(replyto, pSess, producer, errmsg)
        }
        continue
     }

     def command  = ParseMessage(message)
     
     if (!command)
     {
        println "CONTROL: ERROR - Missing parameters"
        if (replyto) 
        {
           def errmsg = "<control_resp>\n" +
               "<status>ERROR: Missing or invalid request parameters</status>\n" +
               "</control_resp>\n"
           j.JMSsendTo(replyto, pSess, producer, errmsg) 
        }
        continue
     }

    def resp = "<control_resp>\n" +  RunCommand(command) + "</control_resp>" 
     
    // Now we handle the response

    if (replyto)
       j.JMSsendTo(replyto, pSess, producer, resp) 

    def elap = ((System.nanoTime() / 1000000) - start).toString() + "ms"     
  } // try...
  
  catch (Exception e)
  {
    println "***WARNING: Exception in Control Service***"
    println e
    println e.printStackTrace()
    if (errcount++ > 100)
       is_shutdown=1
  } 
} // while !shutdown

try
{
   j.JMSterm(cConn, cSess, consumer)
   pSess.close()
   pConn.close()

   def pidfile = new File("${env['FMA_DIR']}/data/Control.pid")
   if (pidfile.exists() && pidfile.canWrite())
      pidfile.delete()

   println "CONTROL: Service now stopped"
   ShowStats(reqs, errcount)
   System.exit(0)
}
catch (Exception e)
{
   println "***CONTROL: Exception processing shutdown - cleanup may be incomplete"
   println e
   println e.printStackTrace()
   System.exit(0)
}
return
