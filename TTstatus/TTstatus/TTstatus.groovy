#!/bin/bash 
//usr/bin/env groovy  -cp 'lib/*'  "$0" $@; exit $?
// #! /usr/bin/env groovy

import javax.mail.*
import javax.mail.internet.*
import groovy.sql.Sql
import groovy.xml.StreamingMarkupBuilder
import groovy.*
import java.sql.Connection
import java.sql.Statement
import javax.sql.DataSource
import java.sql.SQLException
import java.text.*
import java.util.*
import java.util.regex.Pattern
import org.apache.commons.httpclient.HttpClient
import org.apache.commons.httpclient.methods.GetMethod
import org.apache.commons.httpclient.methods.PostMethod
import org.apache.commons.httpclient.UsernamePasswordCredentials
import org.apache.commons.httpclient.methods.StringRequestEntity
import org.apache.commons.httpclient.auth.AuthScope
import org.apache.commons.httpclient.*
import groovy.json.JsonSlurper
import groovy.xml.MarkupBuilder
import groovy.util.*


public class SMTPAuthenticator extends Authenticator
{
    public PasswordAuthentication getPasswordAuthentication()
    {
    	// This is used when connecting as the Sender
        return new PasswordAuthentication('info%40techtiles.net', 'irk4j8xibC!9');
    }
}

def GenerateIndex(this_sub, aid, sql, DEBUG)
{
 try
 {
    def ti = this_sub._TENANT
    def xml = new String(this_sub.content)
    if (!xml || xml.isEmpty())
    {
	println "ERROR: No form data present"
	return "ERROR: No form data present"
    }

    def root = new XmlParser().parseText(xml)
    if (!root || !root.name().equalsIgnoreCase("form"))
    {
 	println "ERROR: Unknown form type - submission ${this_sub.id} AID $aid"
	return ""
    }

    def form = this_sub.FormName
    def sf   = form
    if (!form || form.isEmpty())
        form = "*Unknown*"
    if (form.contains("-") && form.size() > 36)
        try
        {
            UUID uuid = UUID.fromString(form.substring(form.indexOf('-') + 1))     // If this works, then we have a UUID
            sf = form.substring(0, form.indexOf('-'))
            if (DEBUG) println "Dynamic flow $form shortens to $sf"
        } catch (Exception e)
        {
           if (DEBUG) println "FormName doesn't contain a UUID - $form"
        }

    def app = this_sub.FormClass
    if (!app || app.isEmpty())
        app = "*Unknown*"

    tmsg = "<li>Transaction <stronger>${this_sub.ID}</stronger> submitted " + this_sub._CREATED.format("yyyy-MM-dd")

    def whitelist = ["submitter-name", "action"]
    def tran = "Transaction"
    def by = ""
    
    root.depthFirst().each
    {
       if (it.children()?.size() > 1 || !it.text() || !it.value()?.getClass()?.toString().contains("NodeList") || it.attributes())
       {
          if (DEBUG) 
             println "Node ${it.name()} skipped"
       }
       else
       {
          if (whitelist.contains(it.name()))
          {  
             if (it.name().contains("action"))
                tran = it.text()
             if (it.name().contains("name"))
                by = " by " + it.text() + " is "
	  } 
       }
    }
       
    tmsg = "<li>${tran} <stronger>[${this_sub.ID}]</stronger> submitted " + this_sub._CREATED.format("yyyy-MM-dd")
    if (by)
       tmsg += by

    // Next, we show the status
    
    def last_start = ""
    def last_approver = ""

    def r = sql.rows("SELECT * FROM Forms.FormStatus WHERE FormID = ${this_sub.ID}")
    if (r)
    {
	r.each
	{
           last_start = it.Started
           if (it.ActionType?.equalsIgnoreCase("APPROVE")) 
           {
	      def emailID = "*none*"
              def email2  = ""
	      String[] p = it.Parameter.split(";")
	      for (j=0; j < p.length; j++)
	      {
	         String[] this_param = p[j].split("=")
	         if (this_param.length != 2)
	    	    continue
	   	 if (this_param[0].equalsIgnoreCase("email"))
		    emailID = this_param[1].toString()
                 if (this_param[0].equalsIgnoreCase("email2"))
                    email2  = this_param[1].toString()
              }
              if (email2)
	         last_approver = "$emailID or $email2"
              else
	         last_approver = emailID
           } // if ...
    } // r.each ...
    tmsg += "awaiting Approval from $last_approver since $last_start</li>"
} // if (r)
else
    println "No form status rows read"
 
 return tmsg 
}
catch (Exception e)
{
 println "---Exception in GenerateIndex---"
 		println e
                println e.printStackTrace().toString()
 		return ""
 	}
}


def GenerateItem(this_sub, aid, sql, showlinks, engine, DEBUG)
{
 try
 {
    def ti = this_sub._TENANT
    def xml = new String(this_sub.content)
    if (!xml || xml.isEmpty())
    {
	println "ERROR: No form data present"
	return "ERROR: No form data present"
    }

    def root = new XmlParser().parseText(xml)
    if (!root || !root.name().equalsIgnoreCase("form"))
    {
 	println "ERROR: Unknown form type - submission ${this_sub.id} AID $aid"
	return ""
    }

    def form = this_sub.FormName
    def sf   = form
    if (!form || form.isEmpty())
        form = "*Unknown*"
    if (form.contains("-") && form.size() > 36)
        try
        {
            UUID uuid = UUID.fromString(form.substring(form.indexOf('-') + 1))     // If this works, then we have a UUID
            sf = form.substring(0, form.indexOf('-'))
            if (DEBUG) println "Dynamic flow $form shortens to $sf"
        } catch (Exception e)
        {
           if (DEBUG) println "FormName doesn't contain a UUID - $form"
        }

    def app = this_sub.FormClass
    if (!app || app.isEmpty())
        app = "*Unknown*"

    def content  = "<p>Details for transaction <stronger>${this_sub.ID}</stronger> submitted  " + this_sub._CREATED + "</p>" + 	
                   "<div><table style='width:700px'><tr><th align=left bgcolor=#eef>Parameter</th><th align=left bgcolor=#eef>Value</th></tr>"

    def blacklist = [ "action-mode", "submitter-id", "submitter-location", "reason" ]
    
    root.depthFirst().each
    {
       if (it.children()?.size() > 1 || !it.text() || !it.value()?.getClass()?.toString().contains("NodeList") || it.attributes())
       {
          if (DEBUG) 
             println "Node ${it.name()} skipped"
       }
       else
       {
          if (!blacklist.contains(it.name()))
	     content += "<tr><td>" + it.name() + "</td><td>" + it.text() + "</td></tr>"
       }
    }
       
    // Next, we show the status
    
    content += "</table></div>"
    def fstate = "processing"
    def pacts = 0
    def last_start = ""
    def last_approver = ""
    def this_aid = 0

    def r = sql.rows("SELECT * FROM Forms.FormStatus WHERE FormID = ${this_sub.ID}")
    if (r)
    {
	r.each
	{
  	   pacts++
           def desc = "*Unknown Action*"
           def res  = " " 
           def ended = " " 
           if (it.Result)
              res = it.Result
           if (it.Ended) 
              ended = it.Ended
           last_start = it.Started

           if (it.ActionType?.equalsIgnoreCase("APPROVE"))
           {
	      def emailID = "*none*"
              def email2  = ""
              this_aid = it.ID
	      String[] p = it.Parameter.split(";")
	      for (j=0; j < p.length; j++)
	      {
	         String[] this_param = p[j].split("=")
	         if (this_param.length != 2)
	    	    continue
	   	 if (this_param[0].equalsIgnoreCase("email"))
		    emailID = this_param[1].toString()
                 if (this_param[0].equalsIgnoreCase("email2"))
                    email2  = this_param[1].toString()
              }
              if (email2)
	         last_approver = "$emailID or $email2"
              else
	         last_approver = emailID
           } // if...
    } // r.each ...
    content += "<p>Transaction ${this_sub.ID} is awaiting approval from $last_approver since $last_start</p>"
    if (showlinks)
    {
       
       def appurl = engine + '/FormStatus?AID=' + this_aid +'&FRID=' + this_sub.ID + '&tenant=' + ti + '&type=APPROVE'
       def rejurl = engine + '/FormStatus?AID=' + this_aid +'&FRID=' + this_sub.ID + '&tenant=' + ti + '&type=REJECT'
       content += "<p>To <strong>approve</strong> this request, please click <a href='" + appurl + "'>this link</a></p>"  
       content += "<p>To <strong>reject</strong>  this request, please click <a href='" + rejurl + "'>this link</a></p>"  
    }
    content += "<hr />"
} // if (r)
else
    println "No form status rows read"

    return content
}
 	catch (Exception e)
 	{
 		println "---Exception in GenerateReminder---"
 		println e
                println e.printStackTrace().toString()
 		return ""
 	}
}

def SendMail(content, email, t, DEBUG)
{

try
{
	if (!content || !email)
	{
		println "ERROR: SendMail missing/invalid parameter"
		return false
	}

        def title = "Workflow Status Summary"
        if (t)
           title = t 

   def  d_email    = "info@techtiles.info",
        d_uname    = "AKIAJE66JRFQ6BJLP5OQ",
        d_password = "AnmO1nExk3Tj1eSbLnI/aakQkPLxHDAs+Fg0EhueRfFu",
        d_host     = "email-smtp.us-east-1.amazonaws.com",
     	d_port     = 587, //465,587
     	m_to       = email, 
        m_cc       = "",
     	m_subject  = "Workflow Status Summary"

        // DEBUG - No email to targets

        if (DEBUG)
        {
           println "Original target = $m_to"
           m_to = "vince@therefamily.net"
        }

 def props = new Properties()
 props.put("mail.smtp.user", d_email)
 props.put("mail.smtp.host", d_host)
 props.put("mail.smtp.port", d_port)
 props.put("mail.smtp.starttls.enable", "true")
 props.put("mail.smtp.auth", "true")
 props.put("mail.smtp.debug", "true");

	Multipart mp = new MimeMultipart("mixed");
	MimeBodyPart htmlPart = new MimeBodyPart();
	htmlPart.setContent(content.toString(), "text/html");
	mp.addBodyPart(htmlPart);

	def auth = new SMTPAuthenticator()
	def session = Session.getInstance(props, auth)
	if (DEBUG) session.setDebug(true)

	def msg = new MimeMessage(session)
	msg.setContent(mp)
	msg.setSubject(m_subject)
	msg.setFrom(new InternetAddress(d_email))
	msg.addRecipient(Message.RecipientType.TO, new InternetAddress(m_to))

	Transport transport = session.getTransport("smtp");
	transport.connect(d_host, d_port.toInteger(), d_uname, d_password);
	transport.sendMessage(msg, msg.getAllRecipients());
	transport.close();
	println "Status email successfully sent to $m_to"
	return true
}

catch (Exception e)
{
	println "---Exception sending email---"
	println e
	return false
}

}

// START: Main script is here...

def DEBUG     = 0
def now       = new Date().format("dd-MMM-yyyy")
def env       = System.getenv()
def DBHOST    = env["TTDB"]
def DBPORT    = env["TTDBPORT"]
def DBUSER    = env["TTDBUSER"]
def DBPASS    = env["TTDBPASS"]
def DBSCHEMA  = "Forms"
def ti        = 0
def min       = 0
def max       = 0 
def mailid    = ""
def details   = true
def approve   = false
def c         = 0
def total     = 0

if (!DEBUG && env["TTDEBUG"]?.contains("YES"))
{
   DEBUG = 1
   println "Debugging mode - no emails will be sent"
}

if (args.size() < 4)
{
   println "ERROR: Missing argument - should be <tenant-id> <min-age> <max-age> <email>"
}

ti  = args[0]
min = args[1]
max = args[2]
mailid = args[3]
if (env["SUMMARY"] || env["NODETAIL"])
   details = false
if (env["APPROVE"] || env["APPROVELINKS"])
   approve = true

if (!mailid.contains("@") || !mailid.contains("."))
{
  println "ERROR: Invalid email address $mailid"
  System.exit(4)
}

if (!ti || !DBHOST || !DBUSER || !DBPASS || !mailid)
{
  println "ERROR: Missing parameters"
  System.exit(4)
}

println "TTstatus: Tenant $ti Min $min Max $max Mail $mailid"

try
{
   // Initialize SQL connection to the default database

def content   = '<!DOCTYPE html><html><head><meta http-equiv="Content-Type" content="text/html; charset=ISO-8859-1">' +
                "<title>Workflow Status: Pending Requests</title></head><body>" +
                "<div><h3>Status of pending workflow transactions as of $now</h3></div>" +
                "<p>This message contains a summary of all the workflow items submitted within the past $min to $max days that are currently marked as pending approval or rejection.<p>" 

def ix = "<uol>"
def det = ""

   Sql sql = new Sql()
   url = "jdbc:mysql://" + DBHOST + ":" + DBPORT + "/" + DBSCHEMA
   sql = Sql.newInstance(url, DBUSER, DBPASS, "com.mysql.jdbc.Driver")
   if (!sql)
   {
      println "ERROR: Can't connect to database using " + url
      System.exit(4)
   }
   if (DEBUG)
      println "Connected to $url..."

   def tenant = sql.firstRow("SELECT * FROM CEDS_extensions.Tenants where ID=$ti")
   if (!tenant)
   {
      println "ERROR: Invalid tenant $ti"
      sql.close()
      System.exit(4)
   }

   // Get a list of pending forms awaiting approval 
	
   def start = min + " DAY" 
   if (min.contains("hour") || min.contains("day"))
      start = min

   def end = max + " DAY"
   if (max.contains("hour") || max.contains("day"))
      end = max

   def subs = sql.rows("SELECT * FROM FormSubmissions WHERE _TENANT = '" + ti + "' AND IsComplete = '0' AND _CREATED < NOW() - INTERVAL $start AND _CREATED > NOW() - INTERVAL $end")
   if (!subs)
   {
	sql.close()
	println "TTstatus: No pending forms found"
        System.exit(0)
    }
	
    subs.each // Loop here with each pending form/flow in our date range 
    { 
     this_sub ->
        println "Form Submission ${this_sub.ID} created ${this_sub._CREATED} is PENDING"
	total++
        def line = ""
	def hiseq = 0
	def aid = 0
	def stat = sql.firstRow("SELECT * FROM Forms.FormStatus WHERE FormID = ${this_sub.ID} AND ActionType like 'Approve' AND Ended is NULL ORDER BY ActionSequence DESC")
	if (stat)
	{
	   // This means we have an approval that's hanging waiting for a response - that's what we're here for
           hiseq = stat.ActionSequence
	   aid = stat.ID
	   def action = sql.firstRow("SELECT * FROM Forms.FormActions WHERE TargetName=${this_sub.FormName} AND Sequence = $hiseq")
		
	   if (action && action.ActionType.contains("Approve"))
	   {
              if ((line = GenerateIndex(this_sub, aid, sql, DEBUG)))
                  ix += line
	      if ((line = GenerateItem(this_sub, aid, sql, approve, tenant.Engine, DEBUG))) 
                  det += line
	   }
	   else
	       println "Not pending APPROVAL or no actions found for form ${this_sub.FormName} AID $aid SEQ $hiseq"
        }
    }
	
    sql.close()
    if (details)
    {
       ix += "</eol><h3>Details</h3><p>Details for each of the pending transactions shown above follows:</p>"
       content += ix + det + '<br><br><br><hr id="Link">TechTiles Forms Automation (c) 2015-2020, All Rights Reserved</BODY></HTML>'
    }
    else
    {
       ix += "</eol><h3>Details</h3><p>Details for each of the pending transactions shown above are suppressed</p>"
       content += ix + '<br><br><br><hr id="Link">TechTiles Forms Automation (c) 2015-2020, All Rights Reserved</BODY></HTML>'
    }

    // Now send the content 

    SendMail(content, mailid, null, DEBUG)
    
    println "TTstatus: Finished"
    if (DEBUG)
       println content

    System.exit(0)
} // try...

catch (Exception e)
{
	println "---ERROR---"
	println e
	return "Exception during processing \n" + e
}

// All finished  

return "Okay"

