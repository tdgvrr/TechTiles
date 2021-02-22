#!/bin/bash                                                                                                                                 
//usr/bin/env groovy  -cp 'lib/*'  "$0" $@; exit $?

// #! /usr/bin/env groovy

// GetSignature: Fetches a graphic signature for a form if it exists
//
// Syntax: GetXMLSignature <field-name> (and XML must be available on STDIN)
//
// This is part of the TechTiles PDF package
//
// (c) 2018, TechTiles LLC - All Rights Reserved. 
//
// History: V1.0 - March, 2018 - New [VRR]

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
def output    = "" 
def format    = ""
def env = System.getenv() 
def fid = ""  
def atr = ""  
def xml = ""
def val = ""
def TENANT = env["TENANT"]?.toUpperCase()
def DBHOST = env["TTDB"]
def DBPORT = env["TTDBPORT"]
def DBUSER = env["TTDBUSER"]
def DBPASS = env["TTDBPASS"]
def DBSCHEMA = "Forms"

if (args.size() != 2)
{
  printErr "ERROR: Syntax is GetXMLSignature <xml_file> <field>"
  System.exit(4)
}

fid = args[0]
atr = args[1]

if (!fid || !atr)
{
  printErr "ERROR: Missing field"
  System.exit(4)
}  

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
  printErr "Form $fid is ##${r.FormName}## submitted ${r._CREATED}"

  // Get the incoming XML 

  xml = new String(r.Content) 
  def x = new XmlParser().parseText(xml)
  sql.close() 
  x.depthFirst().each 
  { node ->
    if (node.name().equalsIgnoreCase(atr))
       val = node.text()
  }

  if (!val.startsWith("data:image"))
  {
     printErr "ERROR: Unknown string format at $val"
     System.exit(8)
  }

  if (val.contains("png"))
    format = "png"
  else
    if (val.contains("jpg") || val.contains("jpeg"))
       format = "jpg"
    else
    {
       printErr "ERROR: Unknown graphic format in $val"
       System.exit(8)
    }

    byte[] image = val.substring(val?.indexOf(",") + 1)?.trim()?.decodeBase64()

    // Write the binary image to a local temp file here 

    def fn = File.createTempFile("sig", "." + format, null).getAbsolutePath().toString()
    new File(fn).withOutputStream { it.write image } 
    printErr "Signature image written to $fn [" + image.size() + "]" 

    println fn      // Put file name on STDOUT so caller knows where it is
}

catch (Exception e)
{
   System.err.println "ERROR: Exception during processing - $e"
   e.printStackTrace()
   System.exit(4)
}

printErr "Processing complete"
System.exit(0)
