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

def target = []

args.each
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
    return
 }
}

def env = System.getenv()
def (cConn, cSess, consumer, dest) = j.JMSinitTempConsumer()

target.each 
{ 
 service ->
 println "Stopping " + service + " service..."
 def qname = env["JMS_" + service] 
 if (!qname)
 {
    println "ERROR: No queue name for service " + service
    System.exit(4)
 }

 def (pConn, pSess, producer) = j.JMSinitProducer(qname)
 if (!cConn || !pConn)
 {
    println "ERROR: Can't initialize JMS context"
    System.exit(4)
 }

 message = "<stop>"
 j.JMSsendWithReplyTo(pSess, producer, message, dest, TTL_IMMED)

 def message = j.JMSrecv(consumer, 2000)
 if (message)
    println "$service replies: " + message
 else
    println "No respnose from $service service - may not have been running"
 j.JMSterm(pConn, pSess, null)
} 

j.JMSterm(cConn, cSess, consumer)

println "Service shutdown complete"

return
