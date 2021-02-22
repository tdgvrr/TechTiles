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
         
// Load some shared methods from a common source file

def j = new GroovyClassLoader(getClass().getClassLoader()).parseClass(new File("./include/jms.groovy")).newInstance()

// Parse the command line - it's the service to stop 

def RC = 0
def env = System.getenv()
def target = []
def tenant = ""

tenant = env["TENANT"]   

args.each
{ 
 if (it.toUpperCase().startsWith("-T:"))
    tenant = it.substring(3);
 else
 {
  switch (it.toUpperCase())
  {
  case "LOGIN":
  case "LOGOFF":
  case "DB":
  case "CONTROL":
  case "ACTION":
     target << it.toUpperCase()
     break

  case "ALL":
     target << "LOGIN"
     target << "LOGOFF"
     target << "DB"
     target << "CONTROL"
     target << "ACTION"
     break

  default:
     println "ERROR: Unknown service " + it
     System.exit(8)
  }
 }
}

if (!tenant || tenant.size() > 5)
{
   println "ERROR: Missing/Invalid tenant"
   System.exit(8)
}

while (tenant.size() < 5)
  tenant = "0" + tenant
 
def (cConn, cSess, consumer, dest) = j.JMSinitTempConsumer()

target.each 
{ 
 service ->
 println "Ping Tenant " + tenant + " " + service + " service..."
 def qname = "FM." + tenant + "." + service 
 if (!qname)
 {
    println "ERROR: No queue name for service " + service
    System.exit(4)
 }
 println "Queue Name is: " + qname

 def (pConn, pSess, producer) = j.JMSinitProducer(qname)
 if (!cConn || !pConn)
 {
    println "ERROR: Can't initialize JMS context"
    System.exit(4)
 }

 message = "<ping>"
 j.JMSsendWithReplyTo(pSess, producer, message, dest, TTL_IMMED)

 def message = j.JMSrecv(consumer, 30000)
 if (message)
    println "$service replies: " + message
 else
 {
    println "No respnose from $service service - may not have been running"
    RC = 4
 }
 j.JMSterm(pConn, pSess, null)
} 

j.JMSterm(cConn, cSess, consumer)

println "Service Ping complete"

System.exit(RC)
