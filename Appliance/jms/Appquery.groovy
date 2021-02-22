#!/bin/bash                                                                                                                                 
//usr/bin/env groovy  -cp 'lib/*'  "$0" "$1" "$2" "$3" "$4"; exit $?

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

def printErr = System.err.&println

def doDb(j, q, s, p, c, d)
{
 def printErr = System.err.&println
 def result = ""

 try
 {
   def start = System.currentTimeMillis()
   def data = 0

   printErr "Sending query " + q
   j.JMSsendWithReplyTo(s, p, q, d, TTL_IMMED)
   def message = j.JMSrecv(c, 30000)
   if (message)
   {
      data += message.size()
      while (message.contains("<more>Yes"))
      {
          message = j.JMSrecv(c, 30000)
          if (message)
              data += message.size()
          else
              break
      }
   }
   printErr "Received " + data + " bytes in " + (System.currentTimeMillis() - start) + "msec."
   if (data && message)
   {
      printErr "---Response Data---"
      printErr message
   }
 
   def i = 0
   def xml = new XmlSlurper().parseText(message)
   def status = xml.status.text()
   def exception = xml.exception.text()
   def more = xml.more.text()
   def colCount = xml.colCount.text()
   def rowCount = xml.rowCount.text()
   if (exception)
       printErr "Exception: " + exception
   if (status) 
       printErr "Status: " + status
   if (more)
       printErr "More: " + more
   if (colCount)
       printErr "Query returns $colCount columns"
   if (rowCount) 
       printErr "Query returns $rowCount rows"
   xml.row.each
   {
       it.children().each { 
          if (result) result += ";"
          result += it.name() + "=" + java.net.URLDecoder.decode(it.text()) 
       }
   }
 }
 catch (Exception e)
 {
       printErr "ERROR: Exception processing query"
       printErr e.toString()
 }

 return result
}
         
// Load some shared methods from a common source file

def j = new GroovyClassLoader(getClass().getClassLoader()).parseClass(new File("./include/jms.groovy")).newInstance()

def target = ""
def op = "SELECT"
def numrow = "100"
def tnum = ""
def q = ""
def dbname = "DEFAULT"

// First ARG is the tenant - that's the JMS QNAME we'll use
// Second ARG is the operation type - default is SELECT, but can be UPDATE, etc. 
// Third ARG is the query itself in URL-encoded format

printErr "AppQuery args $args"
if (args)
{
   target = tnum = args[0].toUpperCase()
   if (!target.startsWith("FM."))
      target = "FM." + target
   if (!target.endsWith(".DB"))
      target += ".DB"

   if (args.size() > 1)
      op = args[1]?.toUpperCase()      
   
   if (args.size() > 2)
      dbname = args[2]?.toUpperCase()      
   
   if (args.size() > 3)
      q = args[3]      
}

if (!tnum || !tnum?.isNumber() || !op || !dbname || !q)
{
   printErr "ERROR: Missing/Invalid Parameters - $target $op $dbname $q"
   printErr "       Syntax is   DBshell <tenant> <operation> <query>" 
   printErr "       where:" 
   printErr "       <tenant> is the numeric tenant ID (a five-digit string)"
   printErr "       <operation> is the query type, such as SELECT, UPDATE, etc (default = SELECT)"
   printErr "       <dbname> is the database connection name, or DEFAULT"
   printErr "       <query> is a SQL statement in URL-encoded format"
   System.exit(4)
}

// Now setup a few JMS items...
// Messages come back to us over a specific queue name, unique to this instance of the driver

def (cConn,  cSess,  consumer, dest) = j.JMSinitTempConsumer()
def (pConni, pSessi, producerLogin)  = j.JMSinitProducer(target)

if (!cConn || !pConni)
{
   printErr "ERROR: Can't initialize JMS context to $target"
   System.exit(4)
}
printErr "JMS response queue is " + dest.toString() 
printErr "JMS consumer queue is " + consumer.toString() 
printErr "cConn: " + cConn.toString()
printErr "cSess: " + cSess.toString()
printErr "pConni: " + pConni.toString()
printErr "pSessi: " + pSessi.toString()
printErr "cBroker: " + cConn.getBrokerInfo()
printErr "pBroker: " + pConni.getBrokerInfo()

def xml = doDb(j, "<db>" + 
                  "<operation>$op</operation>" + 
                  "<dbname>$dbname</dbname>" + 
                  "<encodedquery>$q</encodedquery>" + 
                  "</db>", 
              pSessi, producerLogin, consumer, dest) 

j.JMSterm(pConni, pSessi, null)
j.JMSterm(cConn, cSess, consumer)

println xml
printErr "Query complete"

System.exit(0)
