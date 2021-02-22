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
import org.apache.commons.net.ftp.FTPClient

@Grab(group='commons-net', module='commons-net', version='3.4')

def SendFTP(host, user, pass, file, dir, port)
{
try 
{
 def ftpClient = new FTPClient()
 ftpClient.connect(host, port)
 ftpClient.enterLocalPassiveMode()
 println(ftpClient.replyString)
 ftpClient.login(user, pass)
 println(ftpClient.replyString)
 ftpClient.changeWorkingDirectory(dir)
 println(ftpClient.replyString)
 ftpClient.fileType=(FTPClient.BINARY_FILE_TYPE)
 println(ftpClient.replyString)

 def incomingFile = new File(file)
 incomingFile.withInputStream 
 { istream ->
    ftpClient.storeFile(file, istream)
 }
 println(ftpClient.replyString);
 ftpClient.disconnect()
}
catch (Exception e)
{
 println "FTP: Can't send $file - $e"
 return
}
}
// Used in the SendMail function

public class SMTPAuthenticator extends Authenticator
{
    public PasswordAuthentication getPasswordAuthentication()
    {
    	// This is used when connecting as the Sender
        return new PasswordAuthentication('info%40techtiles.info', 'password');
    }
}

// Send a simple email

def SendMail(m_to, m_subject, content, attach)
{
 def  d_email    = "info@techtiles.info",
      d_uname    = "AKIAJE66JRFQ6BJLP5OQ",
      d_password = "AnmO1nExk3Tj1eSbLnI/aakQkPLxHDAs+Fg0EhueRfFu",
      d_host     = "email-smtp.us-east-1.amazonaws.com",
      d_port     = 587 //465,587

 def props = new Properties()
 props.put("mail.smtp.user", d_email)
 props.put("mail.smtp.host", d_host)
 props.put("mail.smtp.port", d_port)
 props.put("mail.smtp.starttls.enable", "true")
 props.put("mail.smtp.auth", "true")
 //props.put("mail.smtp.debug", "true")

 Multipart mp = new MimeMultipart("mixed")
 MimeBodyPart htmlPart = new MimeBodyPart()
 htmlPart.setContent(content.toString(), "text/html")
 mp.addBodyPart(htmlPart)
 if (attach)
 {
    MimeBodyPart filePart = new MimeBodyPart()
    filePart.attachFile(attach)
    mp.addBodyPart(filePart)
 }

 try 
 {
    def auth = new SMTPAuthenticator()
    def session = Session.getInstance(props, auth)
    // session.setDebug(true);

    def msg = new MimeMessage(session)
    msg.setContent(mp)
    msg.setSubject(m_subject)
    msg.setFrom(new InternetAddress(d_email))
    msg.addRecipient(Message.RecipientType.TO, new InternetAddress(m_to))

    Transport transport = session.getTransport("smtp");
    transport.connect(d_host, d_port, d_uname, d_password);
    transport.sendMessage(msg, msg.getAllRecipients());
    transport.close();
 }

 catch (Exception e)
 {
	println "---ERROR: Exception sending mail---"
	println e
 }
 return
}

// This is the start of the main script...

def printErr = System.err.&println

try
{
   def now       = new Date().format("dd-MMM-yyyy'T'HHmmss") 
   def counter   = 0
   def output    = "" 
   def env = System.getenv() 
   def target = ""  
   def file = ""
   def tfile = "./templates/MakePDF.template"
   def FORM   = env["FORMNAME"]
   def TENANT = env["TENANT"]

   printErr "$TENANT with $FORM"

   // Args are the file name (in /tmp) and a target, which can be: 
   // 1) a file (file:local_filename) 
   // 2) an email ID (email:emailID or just user@host)
   // 3) an FTP site (ftp:user@password:host/pathname)

   if (args.size() == 3)
   {
     file   = args[0]
     tfile  = args[1]
     target = args[2]
   }

   if (!file || !tfile || !target)
   {
     printErr "ERROR: Missing file, email template or destination"
     System.exit(4)
   }  

   // Expand the email template with the values from the form 
   SimpleDateFormat sdf = new SimpleDateFormat("MMM dd,yyyy HH:mm")    
   Date date = new Date()
   def processt = sdf.format(date)     
   def te = new SimpleTemplateEngine()
   def td = new File(tfile).text
   def vars = [tenant: TENANT, form: FORM, timestamp: processt ]
   def content = te.createTemplate(td).make(vars).toString()
     
   if (!content) 
   {
      printErr "ERROR: Missing/empty content for email"
      System.exit(4)
   }

   // Now we figure out how to dispatch it - save a file, FTP, email
   
   def tparts = target.split(":")
   if (tparts.size() < 2) 
   {
      if (!tparts[0].contains("@"))
      {   
         printErr "ERROR: Invalid target <$target>" 
         System.exit(4)
      }
   }

   switch (tparts[0].trim().toLowerCase()) 
   {
      case "file":
         break

      case "email":
         SendMail(tparts[1].trim(), "About your recent $FORM request", content, file)
         break

      case "ftp": // user@password:host/pathname
         def string = target.replace("@", ":")
         string = string.replaceAll("/", ":")
         def user = "anonymous"
         def pass = "info@techtiles.info"
         def host = ""
         def dir  = ""
         def port = 21
         def f = file
         def p = string.split(":")
         user = p[1]
         pass = p[2]
         host = p[3]
         dir = p[4]
         if (p.size() > 4)
            f = p[5]
         SendFTP(host, user, pass, f, dir, port)
         break

      default: 
         if (tparts[0].contains("@"))
         {
            SendMail(tparts[0].trim(), "About your recent $FORM request", content, file)
            break
         }
         printErr "ERROR: Invalid target <$target>" 
         System.exit(4)

   }

}

catch (Exception e)
{
   printErr "ERROR: Exception during processing - $e"
   e.printStackTrace()
   System.exit(4)
}

printErr "Processing complete"
System.exit(0)
