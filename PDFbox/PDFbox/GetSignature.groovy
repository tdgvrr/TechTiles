#!/bin/bash                                                                                                                                 
//usr/bin/env groovy  -cp 'lib/*'  "$0" $@; exit $?

// #! /usr/bin/env groovy

// GetSignature: Fetches a graphic signature for a form if it exists
//
// Syntax: GetSignature <form-id>
//
// This is part of the TechTiles PDF package
//
// (c) 2016, TechTiles LLC - All Rights Reserved. 
//
// History: V1.0 - March, 2016 - New [VRR]

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
def fid = ""  

if (args.size() == 1)
{
  fid = args[0]
}

if (!fid)
{
  printErr "ERROR: Missing form ID"
  System.exit(4)
}  

def TENANT = env["TENANT"]?.toUpperCase()
def DBHOST = env["TTDB"]
def DBPORT = env["TTDBPORT"]
def DBUSER = env["TTDBUSER"]
def DBPASS = env["TTDBPASS"]
def DBSCHEMA = "Forms"

if (!TENANT || !DBHOST || !DBUSER || !DBPASS)
{
  printErr "ERROR: Missing environment variables"
  System.exit(4)
}

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
   printErr "Connected - $TENANT Database: $url"

   // Now, read the content for the form we were given

   def r = sql.firstRow("SELECT * FROM FormSubmissions WHERE ID=$fid")
   if (!r)
   {
      printErr "ERROR - Form ID $fid is invalid"
      sql.close()
      System.exit(4)
   }
   printErr "Form $fid is ##${r.FormName}## submitted ${r._CREATED} with signature ${r.Signature}"
   
   if (r.Signature)
   {
      def sigid = r.Signature
      def ti = r._TENANT
      r = sql.firstRow("SELECT * FROM Signatures where ID=$sigid and _TENANT=$ti")
      if (!r)
      {
         printErr "ERROR: No signature found for ID $sigid, Tenant $ti"  
         sql.close()
         System.exit(4)
      }
      
      // Write the BLOB to a local temp file here 

      printErr "Signature image created " + r._CREATED + " from " + r.Source 
      def fn = File.createTempFile("sig", ".png", null).getAbsolutePath().toString()
      new File(fn).withOutputStream { it.write r.Signature } 
      printErr "Signature image written to $fn [" + r.Signature.size() + "]" 

      println fn      // Put file name on STDOUT so caller knows where it is
   }
   else
   {
      printErr "ERROR: Form $fid is not signed"
      sql.close()
      System.exit(4)
   }

   sql.close()
}

catch (Exception e)
{
   printErr "ERROR: Exception during processing - $e"
   e.printStackTrace()
   System.exit(4)
}

printErr "Processing complete"
System.exit(0)
