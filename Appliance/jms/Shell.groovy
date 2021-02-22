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
def printErr = System.err.&println

   j.JMSsendWithReplyTo(s, p, q, d, TTL_IMMED)
   def message = j.JMSrecv(c, 10000)
   if (!message)
      printErr "ERROR: No response"
   else
      try
      {
         def xml = new XmlSlurper().parseText(message)
         def stdout = xml.STDOUT.text()
         def stderr = xml.STDERR.text()
         def status = xml.status.text()
         if (status && !stdout && !stderr) 
            printErr status
         if (stdout) println  stdout
         if (stderr) printErr stderr
      }
      catch (Exception e)
      {
         printErr "ERROR: Invalid XML payload"
      }
}
         
// Load some shared methods from a common source file

def j = new GroovyClassLoader(getClass().getClassLoader()).parseClass(new File("./include/jms.groovy")).newInstance()
def env = System.getenv()

def target = "FM.00001.CONTROL"
if (env["JMS_CONTROL"])
   target = env["JMS_CONTROL"]

if (args)
{
   target = args[0].toUpperCase()
   if (!target.startsWith("FM."))
      target = "FM." + target
   if (!target.endsWith(".CONTROL"))
      target += ".CONTROL"
}
   
// Now setup a few JMS items...
// Messages come back to us over a specific queue name, unique to this instance of the driver

def printErr = System.err.&println
def (cConn, cSess, consumer, dest)    = j.JMSinitTempConsumer()
def (pConni, pSessi, producerLogin)  = j.JMSinitProducer(target)
def is_shutdown = 0
def numcmd = 0

if (!cConn || !pConni)
{
   printErr "ERROR: Can't initialize JMS context"
   System.exit(4)
}

printErr "Connected to target $target"

if (args.size() > 1) 
{
   def cmd = "<control> <command>${args[1]}</command></control>"
   doCommand(j, cmd, pSessi, producerLogin, consumer, dest) 
   is_shutdown = 1
   numcmd = 1 
}

def stdin = new InputStreamReader(System.in)
def stdout= new OutputStreamWriter(System.out)

// Have a little shell session...

while (!is_shutdown)
{
   stdout.write("-> ")
   stdout.flush()
   def cmd = stdin.readLine()
   if (!cmd || cmd.equalsIgnoreCase("exit"))
      break
   if (cmd.equalsIgnoreCase("help"))
   {
      println "Enter any command, and it will be executed using the TechTiles CONTROL service." 
      println "Note that interactive commands (editors, etc) CANNOT be used."
      println " "
      println "You are currently connected to " + target 
      println "You have issued " + numcmd + " commands."
      continue
   }
   def i =  "<control> <command>$cmd</command> </control>" 
   doCommand(j, i, pSessi, producerLogin, consumer, dest) 
   numcmd++
}

j.JMSterm(pConni, pSessi, null)
j.JMSterm(cConn, cSess, consumer)

printErr "<Complete - $numcmd commands>"

return
