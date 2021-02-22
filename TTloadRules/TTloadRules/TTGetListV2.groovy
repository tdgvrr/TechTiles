#!/bin/bash                                                                                                                                 
//usr/bin/env groovy  -cp 'lib/*'  "$0" "$1" "$2" "$3" "$4" "$5" "$6" "$7" "$8"; exit $?

// #! /usr/bin/env groovy

// TTGetList272: Fetches a list of keyword=value pairs that can be enriched
//            into a TechTiles workflow 
//
// Syntax: TTGetList <table> <key1> <key2> <key3>
//
// This is part of the TechTiles Dynamic Workflow package
//
// (c) 2019, TechTiles LLC - All Rights Reserved. 
//
// History: V1.0 - Sep, 2020 - New [VRR]
//          V1.1 - Feb, 2020 - Support "email or email" [VRR]

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

def genApprover(text, index, clen)
{
def sep = ";"
 
 if (!clen) 
    sep = ""
 
 if (text.equalsIgnoreCase("NONE"))
    return ""

 if (!text.contains(" or "))
    return "${sep}Approver$index=" + text.trim()
            
 return "${sep}Approver$index=" + text.trim().substring(0, text.trim().indexOf(" or ")).trim() + 
        ";AltApprover$index=" + text.trim().substring(text.trim().indexOf(" or ") + 3).trim() 
}
 
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
def sk1 = ""
def sk2 = ""
def sk3 = ""

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
  printErr "       Syntax is TTGetList <table> <Key1> <Key2> <Key3>"
  println  "MSG=ERROR: Missing parameters"
  System.exit(4)
}  

tb  = args[0]
sk1 = args[1]

if (args.size() > 2)
   sk2 = args[2]
if (args.size() > 3)
   sk3 = args[3]

def k="$sk1/$sk2/$sk3"

if (sk1.startsWith("@") && sk1.contains("="))
{
  def a = sk1.split("=")
  sk1 = a[1]
}

if (sk2.startsWith("@") && sk2.contains("="))
{
  def a = sk2.split("=")
  sk2 = a[1]
}

if (sk3.startsWith("@") && sk3.contains("="))
{
  def a = sk3.split("=")
  sk3 = a[1]
}

printErr "TTGetList272: Fetch using $k on $tb" 

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

   def q = "SELECT * FROM " + tb + " WHERE (Employee = '" + sk1 + "') " +
           " or (Employee = '' AND " +
           "   ((Role = '" + sk2 + "' and Building = '" + sk3 + "') " + 
           " or (Role = '" + sk2 + "' and Building = '') " + 
           " or (Role = '' and Building = '" + sk3 + "'))) " + 
           " ORDER BY Employee desc, Role desc, Building" 

   def r = sql.firstRow(q)

   if (!r)
   {
       printErr "TTGetList: No data for $k in $tb"
       printErr "TTGetList: Query: $q" 
       println "MSG=No data for $k"
       sql.close() 
       System.exit(4) 
   }

// def fields = sql.rows( "describe " + tb + ";");
// fields.Field.each
// {   
//	printErr "Field " + it 
// }
   
   def result = ""
   try 
   { 
      if (r.Approver1)
         result += genApprover(r.Approver1, 1, 0)
   } catch (Exception e) { printErr "No Approver1" }

   try 
   {
      if (r.Approver2)
         result += genApprover(r.Approver2, 2, result.length())
   } catch (Exception e) { printErr "No Approver2" }

   try 
   {
      if (r.Approver3)
         result += genApprover(r.Approver3, 3, result.length())
   } catch (Exception e) { printErr "No Approver3" }

   try 
   {
      if (r.Approver4)
         result += genApprover(r.Approver4, 4, result.length())
   } catch (Exception e) { printErr "No Approver4" }

   try 
   {
      if (r.Notify1)
      {
         if (!r.Notify1.equalsIgnoreCase("NONE"))
            if (result.length() > 1)
               result += ";Notify1=" + r.Notify1.trim()
            else
               result += "Notify1="  + r.Notify1.trim()
      }
   } catch (Exception e) { printErr "No Notify1" }

   try 
   {
      if (r.Notify2)
      {
         if (!r.Notify2.equalsIgnoreCase("NONE"))
            if (result.length() > 1)
               result += ";Notify2=" + r.Notify2.trim()
            else
               result += "Notify2="  + r.Notify2.trim()
      }
   } catch (Exception e) { printErr "No Notify2" }

   try 
   {
      if (r.Notify3)
      {
         if (!r.Notify3.equalsIgnoreCase("NONE"))
            if (result.length() > 1)
               result += ";Notify3=" + r.Notify3.trim()
            else
               result += "Notify3="  + r.Notify3.trim()
      }
   } catch (Exception e) { printErr "No Notify3" }

   try 
   {
      if (r.Notify4)
      {
         if (!r.Notify4.equalsIgnoreCase("NONE"))
            if (result.length() > 1)
               result += ";Notify4=" + r.Notify4.trim()
            else
               result += "Notify4="  + r.Notify4.trim()
      }
   } catch (Exception e) { printErr "No Notify4" }

   if (result.length() > 1)
   {
      result += ";MSG=Approver rule #" + r.ID + " created " + r.Created 
      if (!r.Created.equals(r.Modified))
         result += " modified " + r.Modified
   }
   else
      result = "MSG=WARNING: No approval path found for $sk"

   printErr "Result: $result"
   println result   
}

catch (Exception e)
{
   printErr "ERROR: Exception during processing - $e"
   e.printStackTrace()
   println "MSG=WARNING: Exception during processing"
}

sql.close()
System.exit(0)
