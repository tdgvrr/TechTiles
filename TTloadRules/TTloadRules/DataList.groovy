#!/bin/bash                                                                                                                                 
//usr/bin/env groovy  -cp 'lib/*'  "$0" $@; exit $?

// #! /usr/bin/env groovy

// This is the Data Sync driver
//
// History: V1.0 - August 2015 - New [VRR]

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
import java.util.regex.Pattern
import groovy.util.*

// Allocate and write CSV file heading 

def initCSV(fname, cols, delim)
{
 def output = ""
 writer  = new File(fname).newOutputStream()
 cols.each { output += it + delim }
 output = output.substring(0, output.length() - 1) + "\n"
 writer << output
 println "Created new CSV file - $fname"
 return writer
}

// Write Each Row 

def writeRow(isEmail, writer, cols, rec, delim, counter)
{
def row = ""

  cols.each
  {
    c ->
      def val = rec.getAt(c)?.toString()
      if (!val) 
         val = " "
      if (val.contains(delim))
         val = "'" + val + "'"
      val = val.replace("\n", " ")
      val = val.replace("\r", " ")
      val = val.replace("\t", " ")
      val = val.replace("\f", " ")
      row += val + delim
  }
  row.replaceAll("\n", " ") 
  row = row.substring(0, row.length() - 1) + "\n"
  if (!isEmail)
     println row
  writer << row
}

// This is the start of the main script...

def delim     = ","
def islist    = 0
def counter   = 0
def tenant    = ""
def email     = ""
def fn        = ""
def tnlist    = [ ]
def fnlist    = [:]
def env       = System.getenv()

// Parse the command line arguments  

    def cli = new CliBuilder(usage: 'DataList [-d delim] [-e email] [-f file] [-s table] -t tenant')
    cli.with 
    {
        d longOpt: 'delimiter', args:1, argName: 'delim',    'Specify field delimiter for CSV file, with a default of ","'
        e longOpt: 'email',     args:1, argName: 'email',    'Email destination for result CSV'
        t longOpt: 'tenant',    args:1, argName: 'tenant',   'Specify tenant ID'
        s longOpt: 'select',    args:1, argName: 'table',    'Show specified table name'
        h longOpt: 'help',                                   'Show usage information'
    } 
    def options = cli.parse(args)

    if (!options || options.h) 
    {
        cli.usage()
        return
    }

    if (!options.t) 
    {
        println "ERROR: No arguments - <-t tenant> is required"
        System.exit(4)
    }
    tenant = options.t
    email = options.e

    if (options.d)
    {
       if (options.d.equalsIgnoreCase("\\t"))
          delim = '\t'
       else
         delim = options.d
    }

    // Connect to our database 

    Sql sql = new Sql()
    url = "jdbc:mysql://" + env['TTDB'] + ":" + env['TTDBPORT'] + "/CustomerData" 
    sql = Sql.newInstance(url, env['TTDBUSER'], env['TTDBPASS'], "com.mysql.jdbc.Driver")
    if (!sql)
    {
       println "ERROR: Can't connect to database using " + url
       System.exit(4)
    }

    // Get the name of the target table(s) - can be explicitly set or find the "active" one
 
    def r = sql.rows("SELECT table_name FROM information_schema.tables " +
                     "WHERE table_schema = 'CustomerData' and table_name like '%" + tenant + "%' " +
                     "ORDER BY update_time desc" )
    if (!r || !r?.size())
    {
        println "ERROR: No matching tables found for tenant $tenant"
        sql.close()
        System.exit(4)
    }
    if (options.s)
    {
       println "Processing requested table ${options.s}" 
       tnlist << options.s
    } 
    println "Available data tables for tenant $tenant are:"
    r.each
    {  
        def isAct = sql.firstRow("SELECT id, TargetName from Forms.FormActions where Parameters like '% " + it.table_name + " %'")
        if (isAct)
        { 
            println "    " + it.table_name + " (*ACTIVE in workflow " + isAct.TargetName + "*)"
            tnlist << it.table_name
        }    
        else
            println "    " + it.table_name
    }
    if (!tnlist.size())
    {
        println "ERROR: No tables found for tenant $tenant"
        sql.close()
        def isDel = new File(fn).delete()  
        System.exit(4) 
    }

    // Get the column list (not all tables are the same) and initialize the CSV

    tnlist.each
    {
      tn->
       def cols = [ ]
       def colsUp = [ ]
       File file = File.createTempFile("DataList-",".csv")
       fn = file.toString()
       fnlist.put(tn, fn)
       println "Processing object table $tn for tenant $tenant"
       sql.rows("DESCRIBE CustomerData." + tn).each    
       {
        t ->
          if (!t.Field.equalsIgnoreCase("id") && !t.Field.startsWith("ID"))
          {
             cols << t.Field  
             colsUp << t.Field.toUpperCase()  
          }
       }
       println "$tn containes " + cols.size() + " columns: "
       cols.each { println "    " + it }
       def writer = initCSV(fn, cols, delim)
    
       // Now fetch the data and write the CSV file

       def order = ""
       if (colsUp.contains("EMPLOYEE")) 
          order += "Employee" 
       if (colsUp.contains("ROLE")) 
          order += (order.length() > 0 ? ", Role" : "Role")
       if (colsUp.contains("BUILDING")) 
          order += (order.length() > 0 ? ", Building" : "Building")
           
       sql.rows("SELECT * FROM CustomerData." + tn +" ORDER BY " + order).each    
       {
          writeRow(email, writer, cols, it, delim, counter++)
       }

       writer.close()
    }

    // email the output file if requested

    if (email) 
    {
       println "Emailing results to $email"
       def msg = "<html><body>" +
                 "<p>The current workflow data table(s) for tenant $tenant are attached.</p>" + 
                 "<ul>"
       fnlist.each
       {
          msg += "<li>${it.key} is ${it.value}</li>"
       }
       msg += "<eul></body></html>"
       cmdstring = "/usr/bin/sendemail " +
                   "-f \"OptiGate <Info@TechTiles.net>\" " +
                   "-t \"" + email + "\" " + 
                   "-u \"Workflow data request for tenant " + tenant + "\" " +
                   "-m \"" + msg + "\" " + 
                   "-o \"message-content-type=html\" "
       fnlist.each 
       {       
          cmdstring += "-a " + it.value  + " " 
       }

       List<String> list = new ArrayList<String>()
       Matcher m = Pattern.compile("([^\"]\\S*|\".+?\")\\s*").matcher(cmdstring)
       while (m.find())
       {
          def this_item = m.group(1)
          if (this_item.startsWith('"'))
             this_item = this_item.substring(1)
          if (this_item.endsWith('"'))
             this_item = this_item.substring(0, this_item.length() - 1)
          list.add(this_item)
       }

       def out = new StringBuilder()
       def err = new StringBuilder()
       def proc = list.execute()
       def hrc = proc.waitForProcessOutput(out, err)
       if (out)
          println out
       if (err)
          println "ERROR: " + err

       println "Email sent with RC " + proc.exitValue()
    } 

    println "Finished after $counter rows"
    sql.close()
    fnlist.each
    {
       println "Deleting " + it.value + " for " + it.key
       def isDel = new File(it.value).delete()
    }  

    System.exit(0)
