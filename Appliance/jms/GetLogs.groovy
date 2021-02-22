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
import java.lang.management.ManagementFactory
import java.lang.management.RuntimeMXBean

import static Constants.*

def doCommand(j, q, s, p, c, d)
{
   j.JMSsendWithReplyTo(s, p, q, d, TTL_IMMED)
   def message = j.JMSrecv(c, 10000)
   if (!message)
      println "ERROR: No response"
   else
      try
      {
         def xml = new XmlSlurper().parseText(message)
         def stdout = xml.STDOUT.text()
         def stderr = xml.STDERR.text()
         def status = xml.status.text()
         if (status && !stdout && !stderr) 
            println status
         if (stdout) println java.net.URLDecoder.decode(stdout.toString())
         if (stderr) println java.net.URLEncoder.encode(stderr.toString())
      }
      catch (Exception e)
      {
         println "ERROR: Invalid Response XML payload"
      }
}
         
// Load some shared methods from a common source file

def j = new GroovyClassLoader(getClass().getClassLoader()).parseClass(new File("./include/jms.groovy")).newInstance()
def env = System.getenv()
def numlines = 10
def logs = ""
def user = ""
def pass = ""
def target = "FM.00002.CONTROL"
if (env["JMS_CONTROL"])
   target = env["JMS_CONTROL"]

// ARGS are: 
// -t:<tenant>        Specify tenant-ID 
// -n:<lines>         Specify number of lines to fetch
// -u:<user>          User name (admin AUTH_USER)
// -p:<pass>          Password (admin AUTH_PASS matching above user)
// -l:<log>           Log file - one of DB Control Login Logoff (can be multiple)

args.each
{
   if (it.startsWith("-t:"))
      target = it.substring(3).toUpperCase()
   if (it.startsWith("-u:")) 
      user = it.substring(3)
   if (it.startsWith("-p:")) 
      pass = it.substring(3)
   if (it.startsWith("-n:")) 
      numlines = it.substring(3)
   if (it.startsWith("-l:")) 
      if (!logs) 
         logs = it.substring(3)
      else
         logs += " " + it.substring(3) 
}

// If tenant is just a number, pad it 

if (!target?.toUpperCase().contains("FM") && !target?.toUpperCase().contains("CONTROL"))
   while (target.size() < 5)
      target = "0" + target

if (!target.startsWith("FM."))
   target = "FM." + target
if (!target.endsWith(".CONTROL"))
   target += ".CONTROL"

if (!user || !pass)
{
   println "ERROR: Missing -u <user> -p <password> for the tenant admin user"
   System.exit(4)
}

if (!logs)
   logs = "Login Logoff Control DB"

// Now setup a few JMS items...
// Messages come back to us over a specific queue name, unique to this instance of the driver

def (cConn, cSess, consumer, dest)    = j.JMSinitTempConsumer()
def (pConni, pSessi, producerLogin)  = j.JMSinitProducer(target)
if (!cConn || !pConni)
{
   println "ERROR: Can't initialize JMS context"
   System.exit(4)
}
println "Connected to target $target"

def i =  "<GetLogs>"                  + 
         "<log>$logs</log>"      +
         "<user>$user</user>" + 
         "<lines>$numlines</lines>"          +
         "<pass>$pass</pass>"  +
         "</GetLogs>"

doCommand(j, i, pSessi, producerLogin, consumer, dest) 

j.JMSterm(pConni, pSessi, null)
j.JMSterm(cConn, cSess, consumer)

return
