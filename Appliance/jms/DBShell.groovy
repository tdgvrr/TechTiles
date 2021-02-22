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
import java.io.Console
import jline.*

import static Constants.*

def doCommand(j, q, s, p, c, d)
{
   j.JMSsendWithReplyTo(s, p, q, d, TTL_IMMED)
   def is_more = 1
   def i = 0
   def message = ""

   try
   {
       while (is_more)
       { 
         message = j.JMSrecv(c, 500000)
         if (!message)
         {
            println "ERROR: No response"
            break
         }
         def xml = new XmlSlurper().parseText(message)
         def status = xml.status.text()
         def exception = xml.exception.text()
         def more = xml.more.text()
         def colCount = xml.colCount.text()
         def rowCount = xml.rowCount.text()
         if (exception)
            println "Exception: " + exception
         if (status) 
            println "Status: " + status
         if (more)
            println "More: " + more
         if (colCount)
            println "Query returns $colCount columns"
         if (rowCount) 
            println "Query returns $rowCount rows"
         xml.row.each
         {
            println "---ROW ${++i}---"
            it.children().each { println "   " + it.name() + ": " + java.net.URLDecoder.decode(it.text()) }
         }
         if (!more.equalsIgnoreCase("Yes"))
            is_more = 0
       }
   }
      catch (Exception e)
      {
         println "ERROR: Invalid XML payload"
         println e
         if (message)
         {
            println "---Message---"
            println message
         }
      }
}

def doListProc(j, q, s, p, c, d)
{
   j.JMSsendWithReplyTo(s, p, q, d, TTL_IMMED)
   def is_more = 1
   def i = 0
   def message = ""
   def spname  = ""
   
   try
   {
       while (is_more)
       { 
         message = j.JMSrecv(c, 500000)
         if (!message)
         {
            println "ERROR: No response"
            break
         }
         def xml = new XmlSlurper().parseText(message)
         def status = xml.status.text()
         def exception = xml.exception.text()
         def more = xml.more.text()
         def colCount = xml.colCount.text()
         def rowCount = xml.rowCount.text()
         if (exception)
            println "Exception: " + exception
         if (!status?.contains("OK")) 
            println "Status: " + status
         if (!more?.contains("No"))
            println "More: " + more
         if (!rowCount || rowCount.equals("0")) 
         {
            println "WARNING: Not found"
            return
         }

         xml.row.each
         {
            println "Procedure: ${it.SPECIFIC_CATALOG}.${it.SPECIFIC_SCHEMA}.${it.SPECIFIC_NAME}" 
         }
         if (!more.equalsIgnoreCase("Yes"))
            is_more = 0
       }
   }
      catch (Exception e)
      {
         println "ERROR: Invalid XML payload"
         println e
         if (message)
         {
            println "---Message---"
            println message
         }
      }
}
 

def doShowProc(j, q, s, p, c, d)
{
   j.JMSsendWithReplyTo(s, p, q, d, TTL_IMMED)
   def is_more = 1
   def i = 0
   def message = ""
   def spname  = ""
   
   try
   {
       while (is_more)
       { 
         message = j.JMSrecv(c, 500000)
         if (!message)
         {
            println "ERROR: No response"
            break
         }
         def xml = new XmlSlurper().parseText(message)
         def status = xml.status.text()
         def exception = xml.exception.text()
         def more = xml.more.text()
         def colCount = xml.colCount.text()
         def rowCount = xml.rowCount.text()
         if (exception)
            println "Exception: " + exception
         if (!status?.contains("OK")) 
            println "Status: " + status
         if (!more?.contains("No"))
            println "More: " + more
         if (!rowCount || rowCount.equals("0")) 
         {
            println "WARNING: Not found"
            return
         }

/*
SPECIFIC_CATALOG: nvDemo
SPECIFIC_SCHEMA: SSP
SPECIFIC_NAME: p_TimeOffRequest_Approve
ORDINAL_POSITION: 7
PARAMETER_MODE: IN
IS_RESULT: NO
AS_LOCATOR: NO
PARAMETER_NAME: @AttendanceReason
DATA_TYPE: varchar
CHARACTER_MAXIMUM_LENGTH: 200
CHARACTER_OCTET_LENGTH: 200
COLLATION_CATALOG: null
COLLATION_SCHEMA: null
COLLATION_NAME: SQL_Latin1_General_CP1_CI_AS
CHARACTER_SET_CATALOG: null
CHARACTER_SET_SCHEMA: null
CHARACTER_SET_NAME: iso_1
NUMERIC_PRECISION: null
NUMERIC_PRECISION_RADIX: null
NUMERIC_SCALE: null
DATETIME_PRECISION: null
INTERVAL_TYPE: null
INTERVAL_PRECISION: null
USER_DEFINED_TYPE_CATALOG: null
USER_DEFINED_TYPE_SCHEMA: null
USER_DEFINED_TYPE_NAME: null
SCOPE_CATALOG: null
SCOPE_SCHEMA: null
SCOPE_NAME: null
*/

         xml.row.each
         {
            if (!spname)
            {
               spname = "${it.SPECIFIC_CATALOG}.${it.SPECIFIC_SCHEMA}.${it.SPECIFIC_NAME}" 
               println "---Procedure: ${spname}---" 
            }

            def l = (++i).toString() + ": " + 
                    java.net.URLDecoder.decode(it.PARAMETER_NAME?.toString()) + " " + 
                    java.net.URLDecoder.decode(it.DATA_TYPE?.toString())
            if (it?.DATA_TYPE?.toString()?.contains("varchar"))
                l += " " + it.CHARACTER_MAXIMUM_LENGTH 

            println l 
         }
         if (!more.equalsIgnoreCase("Yes"))
            is_more = 0
       }
   }
      catch (Exception e)
      {
         println "ERROR: Invalid XML payload"
         println e
         if (message)
         {
            println "---Message---"
            println message
         }
      }
}
 

// Load some shared methods from a common source file

this.class.classLoader.rootLoader.addURL( new URL("file://./lib/jline-3.14.0.jar"))
this.class.classLoader.rootLoader.addURL( new URL("file://./lib/jline-groovy-3.14.0.jar"))

def j = new GroovyClassLoader(getClass().getClassLoader()).parseClass(new File("./include/jms.groovy")).newInstance()
def target = ""
def op = "SELECT"
def db = "DEFAULT"
def numrow = "100"
def tnum = ""
def encode = 1  // 1 = Send queries URL-encoded
def debug = 0

// First ARG is the tenant - that's the JMS QNAME we'll use
// Second ARG is the operation type - default is SELECT, but can be UPDATE, etc. 

if (args)
{
   target = tnum = args[0].toUpperCase()
   if (!target.startsWith("FM."))
      target = "FM." + target
   if (!target.endsWith(".DB"))
      target += ".DB"

   if (args.size() > 1)
      op = args[1].toUpperCase()      
   
   if (args.size() > 2)
      db = args[2].toUpperCase()      
}

if (!tnum || !tnum?.isNumber() || !op)
{
   println "ERROR: Missing/Invalid Parameters - $target $op"
   println "       Syntax is   DBshell <tenant> <operation> <dbname>" 
   println "       where:" 
   println "       <tenant> is the numeric tenant ID (a five-digit string)"
   println "       <operation> is the query type, such as SELECT, UPDATE, etc (default = SELECT)"
   println "       <dbname> is the database connection to use at the target (default = DEFAULT)"
   System.exit(4)
}

// Now setup a few JMS items...
// Messages come back to us over a specific queue name, unique to this instance of the driver

def (cConn, cSess, consumer, dest)    = j.JMSinitTempConsumer()
def (pConni, pSessi, producerLogin)  = j.JMSinitProducer(target)
def is_shutdown = 0
def numcmd = 0

if (!cConn || !pConni)
{
   println "ERROR: Can't initialize JMS context"
   System.exit(4)
}

cons = new ConsoleReader();
println "Connected to target $tnum for $op-type queries on database $db"

// Have a little (database) shell session...

while (!is_shutdown)
{
   def i = ""
   def cmd = cons.readLine("DB > ")
   
   if (!cmd || cmd.equalsIgnoreCase("exit"))
      break

   if (cmd.equalsIgnoreCase("help"))
   {
      println "Just enter a $op type query and it will be executed using the TechTiles DB service." 
      println " "
      println "These additional subcommands are built-in:"
      println " "
      println "RESET            Closes/Reopens JDBC connections at the Tenant (use with caution)"
      println "LISTPROC         Displays inventory of stored procedures"
      println "SHOWPROC <proc>  Displays details of a certain procedure"
      println "TYPE     <type>  Switches type of query among SELECT, UPDATE, DELETE, CALL, EXEC"
      println "DB       <db>    Switches between schema (typically DEFAULT or TIMEPIECE)"
      println "ROW      <nnnn>  Limits query output to 'nnnn' rows (default: 100)"       
      println " "
      println "You are currently connected to Tenant $tnum DB $db" 
      println "You have issued $numcmd commands"
      continue
   }

   if (cmd?.trim()?.startsWith("type "))
   {
      def p = cmd?.trim()?.split(" ")

      if (p.size() < 2)
      {
         println "ERROR: Missing parameter - one of SELECT, UPDATE, DELETE, CALL, EXEC" 
         numcmd++
         continue
      }
      op = p[1]?.toUpperCase()?.trim()
      continue
   }
 
   if (cmd?.trim()?.startsWith("db "))
   {
      def p = cmd?.trim()?.split(" ")

      if (p.size() < 2)
      {
         println "ERROR: Missing parameter - need schema/database name" 
         numcmd++
         continue
      }
      db = p[1]?.toUpperCase()?.trim()
      continue
   }
  
   if (cmd?.trim()?.startsWith("rows "))
   {
      def p = cmd?.trim()?.split(" ")

      if (p.size() < 2)
      {
         println "ERROR: Missing parameter - need schema/database name" 
         numcmd++
         continue
      }
      numrow = p[1]?.trim()
      continue
   }
 

   if (cmd.equalsIgnoreCase("debug"))
   {
      debug = 1
      numcmd++
      continue
   }


   if (cmd.equalsIgnoreCase("reset"))
   {
      def r = "<reset></reset>"
      doCommand(j, r, pSessi, producerLogin, consumer, dest)
      numcmd++
      continue
   }

   if (cmd?.trim()?.startsWith("listproc"))
   {
   def p = cmd?.trim()?.split(" ")

   sql = java.net.URLEncoder.encode("select * from information_schema.routines " + 
                                    "where SPECIFIC_SCHEMA='SSP' and routine_type = 'PROCEDURE' ")
   i = "<db>" + 
          " <dbname>$db</dbname>" +
          " <operation>SELECT</operation>" +
          " <encodedquery>$sql</encodedquery>" +
          " <numrows>0</numrows>" +
       "</db>"
      
   doListProc(j, i, pSessi, producerLogin, consumer, dest) 
   numcmd++
   continue
   }
 

   if (cmd?.trim()?.startsWith("show ") || cmd?.trim()?.startsWith("desc"))
   {
   def p = cmd?.trim()?.split(" ")

   if (p.size() < 2)
   {
      println "ERROR: Missing procedure name"
      numcmd++
      continue
   }
   
   sql = java.net.URLEncoder.encode("SELECT * from information_schema.parameters " +
         "where specific_name='" + p[1] + "' ORDER BY ORDINAL_POSITION")
   
   i = "<db>" + 
          " <dbname>$db</dbname>" +
          " <operation>SELECT</operation>" +
          " <encodedquery>$sql</encodedquery>" +
          " <numrows>0</numrows>" +
       "</db>"
      
   doShowProc(j, i, pSessi, producerLogin, consumer, dest) 
   numcmd++
   continue
   }
   
   if (encode)
   {
      cmd = java.net.URLEncoder.encode(cmd)
   
      i = "<db>" + 
          " <dbname>$db</dbname>" +
          " <operation>$op</operation>" +
          " <encodedquery>$cmd</encodedquery>" +
          " <numrows>$numrow</numrows>" +
          "</db>" 
   }
   else
      i = "<db>" + 
          " <dbname>$db</dbname>" +
          " <operation>$op</operation>" +
          " <query>$cmd</query>" +
          " <numrows>$numrow</numrows>" +
          "</db>" 
  
   if (debug) println "---Request---"
   if (debug) println i
   doCommand(j, i, pSessi, producerLogin, consumer, dest) 
   numcmd++
}

j.JMSterm(pConni, pSessi, null)
j.JMSterm(cConn, cSess, consumer)

println "<Complete - $numcmd commands>"

return
