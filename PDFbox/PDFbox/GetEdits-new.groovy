#!/bin/bash                                                                                                                                 
//usr/bin/env groovy  -cp 'lib/*'  "$0" $@; exit $?

// #! /usr/bin/env groovy

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

def printErr = System.err.&println

def WalkFields(item, content)
{
   if (!item)
   {
      System.err.println "WalkFields - no item to walk"
      return content
   }
            
   if (!item.getClass().toString().contains("Node"))
   {  
      System.err.println "WalkFields: Not an XML node"
      return content
   }
                                   
   if (item.name() && item.text())
   {     
      System.err.println "WalkFields Name: " + item.name() + " Class: " + item.getClass().toString() + " Text: " + item.text() + " Attr: " + item.attributes()
                                                   
      if (!item.text().isEmpty() && !item.attributes())   // If it has attributes, it's an attachment
         content.put(item.name(), item.text()) 
   }
   else
   {
      item.children().each
      {
         content = WalkFields(it, content)
      }
   }
   return content
}

// This is the start of the main script...

def now       = new Date().format("dd-MMM-yyyy'T'HHmmss") 
def counter   = 0
def output    = "" 
def env = System.getenv() 
def fid = ""  
def script = ""

if (args.size() == 2)
{
  fid = args[0]
  script = args[1]
}

if (!fid || !script)
{
  printErr "ERROR: Missing form ID or Script Template file"
  System.exit(4)
}  

def TENANT = env["TENANT"]?.toUpperCase()
def DBHOST = env["TTDB"]
def DBPORT = env["TTDBPORT"]
def DBUSER = env["TTDBUSER"]
def DBPASS = env["TTDBPASS"]
def DBSCHEMA = "Forms"

try
{
   // Initialize SQL connection to the default database 

   Sql sql = new Sql();
   url = "jdbc:mysql://${DBHOST}:${DBPORT}/${DBSCHEMA}"
   printErr "Connection URL is $url with $DBUSER/$DBPASS"
   sql = Sql.newInstance(url, DBUSER, DBPASS, "com.mysql.jdbc.Driver")
   if (!sql)
   {
      printErr "ERROR: Can't connect to database using " + url
      System.exit(4)
   }
   printErr "Connected - $TENANT Database: $url"

   // Now, read the content for the form we were given

   def r = sql.firstRow("SELECT * FROM FormSubmissions WHERE ID=$fid AND FormClass=$TENANT")
   if (!r)
   {
      printErr "ERROR - Form ID $fid is invalid"
      sql.close()
      System.exit(4)
   }
   printErr "Form $fid is ##${r.FormName}## submitted ${r._CREATED}"
   def XML = new String(r.Content)
   def x = new XmlParser().parseText(XML)
   if (!x || !x.name().equalsIgnoreCase("form"))
   {
      printErr "ERROR: invalid form type or structure"
      sql.close()
      System.exit(4)
   }
 
   def fvars = [:]
   fvars = WalkFields(x, fvars) 
   printErr "Found these form variables:"
   fvars.each
   {
      printErr "   " + it
   }

   // Finally, expand the template with the values from the form 

   def te = new SimpleTemplateEngine()
   def td = new File(script).text
   def vars = [form: x, formName: r.FormName, formID: fid, tenant: TENANT ]
   output = te.createTemplate(td).make(vars).toString()
   
   if (!output)
   {
      printErr "ERROR: Can't resolve template $script"
      sql.close()
      System.exit(4)
   }

   sql.close()

   // Now echo the output from the template on STDOUT

   println output.trim()
   println "quit"

}

catch (Exception e)
{
   printErr "ERROR: Exception during processing - $e"
   e.printStackTrace()
   System.exit(4)
}

printErr "Processing complete"
System.exit(0)
