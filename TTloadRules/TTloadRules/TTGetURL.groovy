#!/bin/bash                                                                                                                                 
//usr/bin/env groovy  -cp 'lib/*'  "$0" "$1" "$2" "$3" "$4" "$5" "$6" "$7" "$8"; exit $?

// #! /usr/bin/env groovy

// TTGetURL: Fetches a URL for an attachment inside a specific TechTiles form. 
//
// Syntax: TTGetURL <table> <form> <admin-id> <password>  
//
// This is part of the TechTiles Dynamic Workflow package
//
// (c) 2019, TechTiles LLC - All Rights Reserved. 
//
// History: V1.0 - December, 2019 - New [VRR]

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
def rows      = 0
def env       = System.getenv() 
def DBHOST    = env["TTDB"]
def DBPORT    = env["TTDBPORT"]
def DBUSER    = env["TTDBUSER"]
def DBPASS    = env["TTDBPASS"]
def DBSCHEMA  = "Forms"
def target    =""
def filesize  = 0
def filename  = ""
def filetype  = ""
def docid  
def fi        
def tb        
def ti
def tn
def ai
def pa
Sql sql = new Sql()

if (!DBHOST || !DBUSER || !DBPASS)
{
  printErr "ERROR: Missing environment variables"
  System.exit(4)
}

// Setup the arguments

if (!args || args.size() < 4)
{
  printErr "ERROR: Missing parameters"
  printErr "       Syntax is TTGetURL <table> <form> <admin-id> <pass>"
  System.exit(4)
}  
fi = args[0]
tb = args[1]
ai = args[2]
pa = args[3]

printErr "TTGetURL: Fetch attachment URL for form $fi into $tb" 

try
{
   // Initialize SQL connection to the default database 

   url = "jdbc:mysql://" + DBHOST + ":" + DBPORT + "/" + DBSCHEMA
   sql = Sql.newInstance(url, DBUSER, DBPASS, "com.mysql.jdbc.Driver")
   if (!sql)
   {
      printErr "ERROR: Can't connect to database using " + url
      System.exit(4)
   }
   printErr "Connected - Database: $url"

   // First we check the privileges - must have an admin ID/password

   def qauth = "SELECT username, tenant_id FROM FM.fm_admin_users where username = '" + ai + "' " + 
               "and password = md5('" + pa + "')" 
   printErr qauth
   def id = sql.firstRow(qauth)
   if (!id || !id.username.equalsIgnoreCase(ai))
   {
      sql.close()
      println "ERROR: Invalid credentials"
      System.exit(4)
   } 
   ti = id.tenant_id
   tn = new Integer(ti).toString() 

   printErr "User $ai is *AUTHORIZED* for Tenant $ti [$tn]" 

   // Now, read the content of the requested list

   def r = sql.firstRow("SELECT * FROM FormSubmissions WHERE ID=$fi")
   if (!r || ! r.Content)
   {
       printErr "TTGetURL: No data for form ID $r"
       sql.close() 
       System.exit(4) 
   }
   docid = r.DocID 
   def root = new XmlParser().parseText(new String(r.Content))
   root.children().each
   {
      it.children().each
      {
         if (it.name().equalsIgnoreCase("RULEFILE") && it.attributes()) 
         {
            printErr "---> " + it.name() + "=" + it.text()
            target = it.text()
            it.attributes().each
            {
                String[] attr = it.toString().split("=")
	       	if (attr.length == 2)
		{				
                   if (attr[0].equalsIgnoreCase("filename"))
                      filename = attr[1]
                   if (attr[0].equalsIgnoreCase("mediatype"))
                      filetype = attr[1]
                   if (attr[0].equalsIgnoreCase("size"))
                      filesize = attr[1]
	        }
            }
         } 
      }
   }

   def c = sql.firstRow("SELECT * FROM orbeon.orbeon_form_data_attach WHERE document_id = $docid")
   if (!c || !c?.file_content)
   {
      printErr "ERROR: No file content for form $formid"
      sql.close()
      System.exit(4)
   }   
   
   printErr "Form ${c.form} uploaded $filename, type $filetype, size $filesize - internal name ${c.file_name}"
   
   def fd = new String(c.file_content)

   if (fd.length() > 3)
   {
    String bom = String.format("%x", new BigInteger(1, fd.substring(0,2).getBytes()))
    if (bom.startsWith("efbbbf"))
        fd = fd.substring(1) 
    else if (bom.startsWith("feff") || bom.startsWith("ffe") || bom.startsWith("bbbf"))
        fd = fd.substring(1)
   }

   File tf = new File("./data/" + fi + "-" + filename)
   tf.text = fd  
   printErr "Wrote data to " + tf.absolutePath
   
   def q1 = 'START Transaction' 
   def q2 = "truncate table CustomerData. " + tb    
   def q3 = 'LOAD DATA LOCAL INFILE "' + tf.absolutePath + '" ' +  
            "INTO TABLE CustomerData.$tb " + 
            "CHARACTER Set 'Latin1' " +
            "COLUMNS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '" + '"' + "' " +
            "LINES TERMINATED BY '\\r\\n' (Employee, Approver1, Approver2, Approver3); "  
   def q4 = "COMMIT; " 
 
   printErr q1
   sql.execute(q1) 
   printErr q2
   sql.execute(q2) 
   printErr q3
   sql.execute(q3)
   rows = sql.getUpdateCount()     
   printErr q4
   sql.execute(q4) 

   // tf.delete() 
              
}

catch (Exception e)
{
   printErr "ERROR: Exception during processing - $e"
   e.printStackTrace()
}

sql.close()
println "Processing complete for form $fi  - table $tb now $rows rows from $filename, type $filetype, size $filesize"
System.exit(0)
