#!/usr/bin/env groovy 

//
// Simple Groovy script to edit a file having KEYWORD = VALUE settings
//
// Syntax: FileEdit <options> 
// 
// Where <options> are: 
// --add      	              Adds the statement if not found
// --debug                    Show some debugging information
// --quote         <opt>      Encode special characters, opt=B, 64, Q 
// --global                   Allow keyword anywhere on non-comment lines
// --comment-char  <char>     Specify the character string signifying a comment
// --prefix        <char>     Optional prefix that can precede keyword
// --file          <file>     Name of the file to process
// --keyword       <kwd>      Line(s) to find
// --value         <val>      New value for <kwd>
//
// Mar 20, 2020 - VRR: New
//

import java.text.*
import groovy.text.*
import java.util.regex.*
import java.util.*
import groovy.*
import groovy.io.FileType

def printErr = System.err.&println
 
// Parse the command line options 

def ParseCommand()
{
 def printErr = System.err.&println
 
 def cli = new CliBuilder(usage:'FileEdit [options] ', header: 'options:')
 cli.help('print this message')
 cli.a(longOpt:'add',          'add statement if not found in current file')
 cli.d(longOpt:'debug',        'generate debugging information')
 cli.g(longOpt:'global',       'change keyword anywhere on lines')
 cli.q(longOpt:'quote',        args:1, argName:'qt',  'quote special characters using quotes, Bash or Base64 encoding')
 cli.c(longOpt:'comment-char', args:1, argName:'cc',  'specify the comment character')
 cli.p(longOpt:'prefix',       args:1, argName:'pre', 'optional prefix before keyword')
 cli.f(longOpt:'file',         required:true, args:1, argName:'file',    'name of the settings file to edit')
 cli.k(longOpt:'keyword',      required:true, args:1, argName:'keyword', 'setting keyword to edit')
 cli.v(longOpt:'value',        required:true, args:1, argName:'value',   'new value for keyword')
 def options = cli.parse(args)

 if (!options) 
    System.exit(4) 

 if (options.d)
 {
    printErr "args = ${args.join(' ')}"
    printErr "options.a = $options.a"
    printErr "options.q = $options.q"
    printErr "options.g = $options.g"
    printErr "options.c = $options.c"
    printErr "options.p = $options.p"
    printErr "options.f = $options.f"
    printErr "options.k = $options.k"
    printErr "options.v = $options.v"
 }  

 if (options.help) 
    cli.usage()
 
 return options
}

// Fixup the replacement string if needed

def EncodeString(opt, repl)
{
def esc = false

 if (!opt.q)
    return repl

 repl.toCharArray().each()
 { c->
    if (!Character.isDigit(c) && !Character.isLetter(c))
       esc = true
 }

 if (!esc) 
   return repl

 // First option is to just single-quote the string

 if (opt.q.startsWith("Q"))
 {
    if (repl.contains("'"))
       repl = repl.replace("'", "''")
    repl = "'" + repl + "'"
    return repl
 } 

 // Next option is the BASH-script style: $'var' 
 // This lets us escape the special characters

 if (opt.q.startsWith("B"))
 {
    repl = repl.replace("'", '\\' + "'")
    repl = repl.replace('"', '\\' + '"')
    repl = '$' + "'" + repl + "'" 
    return repl
 }

 if (opt.q.startsWith("64"))
 {
    return repl.bytes.encodeBase64().toString()
 }

 if (opt.q.startsWith("URL"))
 {
    return java.net.URLEncoder.encode(repl).toString()
 }

 println "ERROR: Invalid quote encoding method at ${opt.q}" 
 println "Valid methods are: "
 println "Q             Simple quoted string"
 println "BASH          Encoded using BASH \$\'string\' syntax"
 println "64            Base-64 encoding"
 println "URL           URL-encoded strings"

 System.exit(8)
}


// Main routine starts here...

def now = new Date().format('yyyy-MM-dd@HH:mm:ss')
def ln  = 1

try
{
 // Parse the options 
 
 def opt = ParseCommand()
 def fn      = opt.f
 def kwd     = opt.k 
 def repl    = opt.v
 def pref    = ""
 if  (opt.p)
    pref    = opt.p
 def comment = "#"      // Default value
 if (opt.c)
    comment = opt.c
 def missing = 1	// Default value
 if (opt.a)
    missing = opt.a
 def quote = 0 
 repl = EncodeString(opt, repl)
    
 // Open the file and read it - these are small files so they fit in memory

 def file  = new File(fn) 
 def lines = file.readLines()
 def out   = [] 
 def found = false
 
 lines.each()
 { l ->
      if (opt.d)
         printErr "Line #" + String.format('%03d', ln++) + " | " + l 
      if (!l.trim() || l.trim().startsWith(comment))
         out.add(l)
      else
      { 
         // Parse the line into keyword and value

         String[] parts = l.split('=')
         if (parts.size() != 2)
            out.add(l)
         else
         {
            def k = parts[0]?.trim()
            def v = parts[1]?.trim()
         
            if  (k.equalsIgnoreCase(kwd))
            {
               out.add("# $l  <== changed $now")
               out.add("$kwd=$repl")
               found=true
            }
            else
               if (pref && k.contains(kwd) && k.contains(pref))
               {
                  String[] keyparts = k.trim().split(" ")
                  if (keyparts.size() > 1 && keyparts[0].trim().equalsIgnoreCase(pref))
                  {
                     out.add("# $l <== changed $now")
                     out.add("${keyparts[0]} $kwd=$repl")
                     found=true
                  } 
                  else
                     out.add(l)
               }   
               else
                  out.add(l)
         }
     }
 }

 if (opt.a && !found)
    if (pref)
       out.add("$pref $kwd=$repl")
    else
       out.add("$kwd=$repl")

 // Write the file(s)

 file.renameTo(fn + "." + now)

 new File(fn).withWriter 
 { w ->
    out.each() 
    { line ->
        w.writeLine line
    }
 }

 if (opt.d)
    out.each() { printErr it }
}

catch (Exception e)
{
 printErr "***EXCEPTION***"
 printErr e.toString()
}

return
