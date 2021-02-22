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

// This is the start of the main script...

def printErr = System.err.&println
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
   def XML = new String(r.Content)
   def x = new XmlParser().parseText(XML) 
   if (!x || !x.name().equalsIgnoreCase("form"))
   {
      printErr "ERROR: invalid form type or structure"
      sql.close()
      System.exit(4)
   }

   // new XmlNodePrinter(preserveWhitespace:true).print(x)
   
   def fvars = [:]
   x.depthFirst().each 
   { node ->
      def prefix = ""
      def this_node
      if(node.children().size() == 1)
      {
         for (this_node = node; this_node.parent(); this_node = this_node.parent())
            if (!this_node.parent().name().equalsIgnoreCase("form") && !this_node.parent().name().startsWith("section-"))
               prefix = this_node.parent().name() + "." + prefix
         fvars.put(prefix + node.name(), node.text())
      }
   }

   printErr "Found these form variables:"
   fvars.each
   {
      printErr "   " + it
   }

  // Finally, expand the template with the values from the form 
  //              ${x.'section-19'.'grid-21'[0].Part4Name.text()}

   def te = new SimpleTemplateEngine()
   def td = new File(script).text
   def vars = [form: fvars, xml:x, formName: r.FormName, formID: fid, tenant: TENANT ]
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
   if (!output.endsWith("quit"))
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
