#!/bin/bash                                                                                                                                 
//usr/bin/env groovy  -cp 'lib/*'  "$0" "$1" "$2" "$3" "$4" "$5" "$6" "$7" "$8"; exit $?

// #! /usr/bin/env groovy

// TTlist: Fetches a dynamic list from the TechTiles database for a tenant
//
// Syntax: TTlist <tenant> <list-type> <list-name> 
//
// Returns all of the values from the dynamic list, one per line in proper sequence
//
// This is part of the TechTiles Dynamic Workflow package
//
// (c) 2017, TechTiles LLC - All Rights Reserved. 
//
// History: V1.0 - March, 2017 - New [VRR]

import groovy.sql.Sql
import java.sql.Connection
import java.sql.Statement
import java.sql.SQLException
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
import javax.mail.*
import javax.mail.internet.*

// This is the start of the main script...

def printErr = System.err.&println
def now       = new Date().format("dd-MMM-yyyy'T'HHmmss") 
def counter   = 0
def output    = "" 
def env = System.getenv() 
def TENANT = env["TENANT"]?.toUpperCase()
def DBHOST = env["TTDB"]
def DBPORT = env["TTDBPORT"]
def DBUSER = env["TTDBUSER"]
def DBPASS = env["TTDBPASS"]
def DBSCHEMA = "CEDS_extensions"

if (!TENANT || !DBHOST || !DBUSER || !DBPASS)
{
  printErr "ERROR: Missing environment variables"
  System.exit(4)
}

// Setup the arguments

printErr "TTLIST: $args"

if (args.size() < 4)
{
  printErr "ERROR: Missing parameters"
  printErr "       Syntax is TTlist <tenant-id> <list-type> <list-name>"
  System.exit(4)
}  
def ti = args[0]
def lt = args[1]
def ln = args[2]

try
{
   // Initialize SQL connection to the default database 

   Sql sql = new Sql();
   url = "jdbc:mysql://" + DBHOST + ":" + DBPORT + "/" + DBSCHEMA
   sql = Sql.newInstance(url, DBUSER, DBPASS, "com.mysql.jdbc.Driver")
   if (!sql)
   {
      printErr "ERROR: Can't connect to database using " + url
      System.exit(4)
   }
   printErr "Connected - $TENANT ($ti) Database: $url"

   // Now, read the content of the requested list

   sql.rows("SELECT * FROM Lists WHERE _TENANT=$ti AND Type=$lt AND Name=$ln ORDER BY Seq").each
   { r->
       printErr "TTList: Record ${r.ID} Seq ${r.Seq} is ${r.value}"
       println r.Value
       counter++
   }
   sql.close()

   if (!counter)
   {
      printErr "ERROR - No data found for Tenant $ti, List $lt = $ln"
      System.exit(4)
   }
   printErr "Returned $counter rows for Tenant $ti, List $lt = $ln" 
   
}

catch (Exception e)
{
   printErr "ERROR: Exception during processing - $e"
   e.printStackTrace()
   System.exit(4)
}

printErr "Processing complete"
System.exit(0)
