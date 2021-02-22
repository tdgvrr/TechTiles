#!/bin/bash                                                                                                                                 
//usr/bin/env groovy  -cp 'lib/*'  "$0" "$1" "$2" "$3" "$4" "$5" "$6" "$7" "$8"; exit $?

// #! /usr/bin/env groovy

// TTGetList: Fetches a list of keyword=value pairs that can be enriched
//            into a TechTiles workflow 
//
// Syntax: TTGetList <key> <table> 
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

if (!args || args.size() < 2)
{
  printErr "ERROR: Missing parameters"
  printErr "       Syntax is TTGetList <key> <table>"
  println  "MSG=ERROR: Missing parameters"
  System.exit(4)
}  
sk = args[0]
tb = args[1]

if (sk.startsWith("@") && sk.contains("="))
{
  def a = sk.split("=")
  sk = a[1]
}

printErr "TTGetList: Fetch using $sk on $tb" 

try
{
   // Initialize SQL connection to the default database 

   url = "jdbc:mysql://" + DBHOST + ":" + DBPORT + "/" + "CustomerData"
   sql = Sql.newInstance(url, DBUSER, DBPASS, "com.mysql.jdbc.Driver")
   if (!sql)
   {
      printErr "ERROR: Can't connect to database using " + url
      System.exit(4)
   }
   printErr "Connected - Database: $url"
 
   // Now, read the content of the requested list

   def r = sql.firstRow("SELECT * FROM " + tb + " WHERE Employee='" + sk + "'")
   if (!r)
   {
       printErr "TTGetList: No data for $sk in $tb"
       println "MSG=No data for $sk"
       sql.close() 
       System.exit(4) 
   }

   def fields = sql.rows( "describe " + tb + ";");
   fields.Field.each
   {   
	printErr "Field " + it 
	
   }
   
   def result = ""
  
   try { 
   if (r.Approver1)
   {
      if (!r.Approver1.equalsIgnoreCase("NONE"))
         result = "Approver1=" + r.Approver1.trim()
   }
   } catch (Exception e) { printErr "No Approver1" }

   try {
   if (r.Approver2)
   {
      if (!r.Approver2.equalsIgnoreCase("NONE"))
         if (result.length() > 1)
            result += ";Approver2=" + r.Approver2.trim()
         else
            result += "Approver2=" + r.Approver2.trim()
   }
   } catch (Exception e) { printErr "No Approver2" }

   try {
   if (r.Approver3)
   {
      if (!r.Approver3.equalsIgnoreCase("NONE"))
         if (result.length() > 1)
            result += ";Approver3=" + r.Approver3.trim()
         else
            result += "Approver3="  + r.Approver3.trim()
   }
   } catch (Exception e) { printErr "No Approver3" }

   try {
   if (r.Approver4)
   {
      if (!r.Approver4.equalsIgnoreCase("NONE"))
         if (result.length() > 1)
            result += ";Approver4=" + r.Approver4.trim()
         else
            result += "Approver4="  + r.Approver4.trim()
   }
   } catch (Exception e) { printErr "No Approver4" }

   try {
   if (r.Notify1)
   {
      if (!r.Notify1.equalsIgnoreCase("NONE"))
         if (result.length() > 1)
            result += ";Notify1=" + r.Notify1.trim()
         else
            result += "Notify1="  + r.Notify1.trim()
   }
   } catch (Exception e) { printErr "No Notify1" }

   try {
   if (r.Notify2)
   {
      if (!r.Notify2.equalsIgnoreCase("NONE"))
         if (result.length() > 1)
            result += ";Notify2=" + r.Notify2.trim()
         else
            result += "Notify2="  + r.Notify2.trim()
   }
   } catch (Exception e) { printErr "No Notify2" }

   try {
   if (r.Notify3)
   {
      if (!r.Notify3.equalsIgnoreCase("NONE"))
         if (result.length() > 1)
            result += ";Notify3=" + r.Notify3.trim()
         else
            result += "Notify3="  + r.Notify3.trim()
   }
   } catch (Exception e) { printErr "No Notify3" }


   if (result.length() > 1)
   {
      result += ";MSG=Approver rule #" + r.ID + " created " + r.Created 
      if (!r.Created.equals(r.Modified))
         result += " modified " + r.Modified
   }
   else
      result = "MSG=WARNING: No approval path found for $sk"
   println result   
}

catch (Exception e)
{
   printErr "ERROR: Exception during processing - $e"
   e.printStackTrace()
}

sql.close()
System.exit(0)
