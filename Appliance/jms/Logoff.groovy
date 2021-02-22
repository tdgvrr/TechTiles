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


// Cleanup stale session tokens (older than two days)

def DeleteToken(sess)
{
 def env = System.getenv()
 def dir = env['FMA_DIR']
 if (!dir) dir = "/appliance"
 if (dir.endsWith("/")) 
    dir = dir + "data/sessions/"
 else 
    dir = dir + "/data/sessions/"
 
 try
 {
    def fname = dir + sess + ".session"
    def token = new File(fname).text
    if (!token)
    {
       println "Logoff: Invalid session - $sess"
       return null 
    }
    
    if (!token.startsWith("ID:"))
    {
       println "Logoff: Invalid token format at $token"
       return null
    }

    println "Logoff: Found token $token"

    // Token is ID:xxxx/SOURCE:xxxx/TOD:xxxx 
    
    def x = token.substring(3).split("/SOURCE")
    
    def rc = new File(fname).delete()
    if (!rc)
       println "Logoff: Warning - couldn't delete session file $fname"

    if (x.size() > 0)
       return x[0]    // Return the user name  
    return "*Unknown*"
 }
 catch (Exception e)
 {
    println "Logoff: Exception cleaning up session $sess"
    println e
    return "*Unknown*"
 }
}

// Parse incoming XML stream into variables

def ParseMessage(message)
{
  // Here with a request to run in "message" 
  // It's XML and specific to the service (logoff) 
  // <logoff>
  //   <session>  -session token- </session>
  // </logoff>

     def sess = ""

     try
     {
        def xml = new XmlSlurper().parseText(message)
        sess = xml.session.text()
        return sess
     }
     catch (Exception e)
     {
        println "ERROR: Invalid XML input"
        return null
     }
}

// Main routine starts here... 
     
// Load some shared methods from a common source file

def j = new GroovyClassLoader(getClass().getClassLoader()).parseClass(new File("./include/jms.groovy")).newInstance()
def startup = new Date()
def is_shutdown = false
def reqs = 0
def errcount = 0
def env = System.getenv()
def qname = env['JMS_LOGOFF']
if (!qname)
{
   println "ERROR: No queue name for this tenant and service [JMS_LOGOFF]"
   System.exit(4)
} 

def (pConn, pSess, producer) = j.JMSinitProducer(null)
def (cConn, cSess, consumer, dest) = j.JMSinitConsumer(qname)
if (!cConn)
{
   println "ERROR: Can't create JMS connection using $qname"
   System.exit(4)
}

println "System LOGOFF Service Now READY on Queue " + qname
def sys = "${System.getProperty('os.name')} ${System.getProperty('os.version')} on ${System.getProperty('os.arch')}"
println "Logoff: System $sys with Groovy ${GroovySystem.version} and Java ${System.getProperty('java.version')}"

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
           println "Logoff: WARNING - JMS Connection closed...reconnecting"

           try
           {
              j.JMSterm(cConn, cSess, consumer)
              pSess.close()
              pConn.close()
           }
           catch (Exception e)
           {
              println "Logoff: Session cleanup inconplete"
           }
           def connected = 0
           while (!connected)
           {
              (pConn, pSess, producer) = j.JMSinitProducer(null)
              if (!pConn)
              {
                  println "Logoff: Reconnect failed/producer - retrying $qname"
                  sleep(30*1000)
                  continue
              }
              println "Logoff: JMS reconnect - producer connected for $qname"
              (cConn, cSess, consumer, dest) = j.JMSinitConsumer(qname)
              if (!cConn)
              {
                  println "Logoff: Reconnect failed/consumer - retrying $qname"
                  sleep(30*1000)
                  continue
              }
              println "Logoff: JMS reconnect - consumer connected for $qname"
              println "Logoff: JMS reconnect succeeded for $qname"
              startup = new Date()
              connected = 1
              break
           }
        }
        System.gc()
        ShowStats(reqs, errcount)
        errcount = 0
        println "Logoff: Waiting for a request..."
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
           println "Logoff: Stale shutdown message ignored from $tstr"
           continue
        }

        println "Logoff: Shutdown request received"
        is_shutdown = 1
        if (replyto)
        {  
           def errmsg = "<logoff_resp>\n" +
               "<status>SHUTDOWN</status>\n" +
               "<session>*NONE*</session>\n" +
               "<message>The service is shutting down</message>\n" +
               "<role>*NONE*</role>\n" +
               "</logoff_resp>\n"
           j.JMSsendTo(replyto, pSess, producer, errmsg)
        }
        break
     } 

     // Check for a "Ping" request 
     
     if (message.contains("<ping>"))
     {
        println "Logoff: PING request received"
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

     def sess = ParseMessage(message)
     
     if (!sess)
     {
        println "Logoff: ERROR - Missing parameters"
        if (replyto) 
        {
           def errmsg = "<logoff_resp>\n" +
               "<status>ERROR</status>\n" +
               "<session>*NONE*</session>\n" +
               "<message>Missing or invalid request parametes</message>\n" +
               "<role>*NONE*</role>\n" +
               "</logoff_resp>\n"
           j.JMSsendTo(replyto, pSess, producer, errmsg) 
        }
        continue
     }

     def user = DeleteToken(sess) 
    
     // Now we handle the response

     if (replyto)
     {
       def resp = "<logoff_resp>\n" + 
                  "<status>" + (user ? "OK" : "ERROR") + "</status>\n" +  
                  "<session>$sess</session>\n" +
                  "<message>" + (user ? "User $user is now logged off" : "An error occurred") + "</message>\n" + 
                  "</logoff_resp>\n"
       j.JMSsendTo(replyto, pSess, producer, resp) 
     }

     def elap = ((System.nanoTime() / 1000000) - start).toString() + "ms"     
     println "Logoff: User $user now logged off, $elap" 
  } // try...
  
  catch (Exception e)
  {
    println "***Exception in Logoff Service***"
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

   pidfile = new File("${env['FMA_DIR']}/data/Logoff.pid")
   if (pidfile.exists() && pidfile.canWrite())
      pidfile.delete()
}
catch (Exception e)
{
   println "Logoff: Shutdown incomplete"
   println e
   println e.printStackTrace()
}

ShowStats(reqs, errcount)
println "Logoff: Service now stopped"
System.exit(0)
