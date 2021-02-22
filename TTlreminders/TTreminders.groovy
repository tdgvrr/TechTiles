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

def GenerateReminder(this_sub, aid, sql, DEBUG)
{
 try
 {
    def ti = this_sub._TENANT
    def xml = new String(this_sub.content)
    if (!xml || xml.isEmpty())
    {
	println "No form data present"
	return ""
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

    if (this_sub.SubmitID != null)
        tmsg = "Reminder: <stronger>$sf [ID ${this_sub.ID}]</stronger> was submitted by " + this_sub.SubmitID + " at " + this_sub._CREATED
    else if (sf.equals(form))
	tmsg = "Reminder: <stronger>$sf [ID ${this_sub.ID}]</stronger> was submitted by an anonymous user at " + this_sub._CREATED
    else
	tmsg = "Reminder: <stronger>$sf [ID ${this_sub.ID}]</stronger> was submitted at " + this_sub._CREATED

def content = '<!DOCTYPE html><html><head><meta http-equiv="Content-Type" content="text/html; charset=ISO-8859-1">' 
    content += "<title>Reminder: Approve $sf [${this_sub.ID}]</title></head><body>"
    content += "<div><h3>Please review and approve: $sf [ID ${this_sub.ID}]</h3></div>"
    content += "<p>" + tmsg + "</p>"
    content += "<p>You are receiving this message because a previously submitted item is pending. Please review and approve or reject this item.<p>"
    content += "<div><table style='width:700px'><tr><th align=left bgcolor=#eef>Parameter</th><th align=left bgcolor=#eef>Value</th></tr>"

    root.depthFirst().each
    {
       if (it.children()?.size() > 1 || !it.text() || !it.value()?.getClass()?.toString().contains("NodeList") || it.attributes())
       {
          if (DEBUG) 
             println "Node ${it.name()} skipped"
       }
       else
	  content += "<tr><td>" + it.name() + "</td><td>" + it.text() + "</td></tr>"
    }
       
    // Next, we show the status
    
    content += "</table></div><h3>Current status of this request</h3>"
    def fstate = "processing"
    def pacts = 0
    def last_start = ""
    def r = sql.rows("SELECT * FROM Forms.FormStatus WHERE FormID = ${this_sub.ID}")
    if (r)
    {
	content += "<table style='width:800px'><tr><th align=left bgcolor=#eef>Action</th><th align=left bgcolor=#eef>Result</th><th align=left bgcolor=#eef>Time</th></tr>"
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

           switch (it.ActionType?.toUpperCase())
           {
           case "APPROVE": 
	   
              // Get the email ID from the parameters

	      def emailID = "*none*"
              def email2  = ""
	      if (!res || res.equals(" "))
                 res = "Pending"
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
	         content += "<tr><td>Request approval from $emailID or $email2</td><td>" + res + "<td>" + ended + "</td></tr>"
              else
	         content += "<tr><td>Request approval from " + emailID + "</td><td>" + res + "<td>" + ended + "</td></tr>"
              break
	
        case "NOTIFY":
	
             // Get the email ID from the parameters

	    def emailID = "*none*"
	    String[] p = it.Parameter.split(";")
	    for (j=0; j < p.length; j++)
	    {
	    	String[] this_param = p[j].split("=")
	    	if (this_param.length != 2)
         	    continue
		if (this_param[0].equalsIgnoreCase("email"))
		    emailID = this_param[1].toString()
	    }
	    content += "<tr><td>Send notification to " + emailID + "</td><td>" + res + "<td>" + ended + "</td></tr>"
            break

    	case "RUN":
          
	    // Get the thing we ran from the parameters
	    def out = "Okay"
	    if (it.Result.toString().trim().startsWith("ERROR"))
		out = "Error"	
	    content += "<tr><td>Ran integration process " + it.Parameter + "</td><td>" + out + "<td>" + it.Ended + "</td></tr>"
	    break

        default:

	    content += "<tr><td> ${it.ActionType} with ${it.Parameter} </td><td>${res} <td> ${ended}</td></tr>"
            break
        } // switch...
    } // r.each ...
    content += "</table><br />"
} // if (r)
else
    println "No form status rows read"

    s = sql.firstRow("SELECT COUNT(*) AS T FROM Forms.FormActions WHERE TargetName = $form AND _TENANT = $ti")	
    if (!s)
    {
	println "WARNING: No actions for form $form"
	return ""
    }
		
    if (s.T == pacts)
        content += "<p>Processing is complete</p></div>" 
    else
        if (last_start)
           content += "<p>Item is at step " + pacts + " out of " + s.T + " total processing steps since $last_start</p></div>" 
        else
           content += "<p>Item is at step " + pacts + " out of " + s.T + " total processing steps</p></div>" 

		// Now setup content for actions (approve, reject, etc)
		// Approve/Reject come back to us on another URL

		def ix = sql.firstRow("SELECT * FROM Forms.FormIndex WHERE _TENANT=$ti AND FormName = $sf")
		if (!ix)
    		ix = sql.firstRow("SELECT * FROM Forms.FormIndex WHERE _TENANT=$ti AND EngineForm = $sf")
		if (!ix)
		{
			println "WARNING: Undefined form $sf ($form)"
			return ""
		}
		
		def sh = ""  
		if (ix && ix.StatusHost)
			sh = ix.StatusHost
		if (ix && ix.StatusPort)
			sh += ":" + ix.StatusPort
		if (ix && ix.EngineHost)
			eh = ix.EngineHost;
		
		def baseurl = 'http://' + sh + '/FormStatus?AID=' + aid + '&FRID=' + this_sub.ID + '&tenant=' + ti 
		
		content += "<div><h3>Reminder: This item requires your approval...</h3><ul>"
		content += '<li>To <strong>approve</strong> this item, please click <a href="' + baseurl + '&type=APPROVE">here</a></li>'
		content += '<li>To <strong>reject</strong> this item, please click <a href="' + baseurl + '&type=REJECT">here</a></li>'
		content += "</div>"

		content += "<br><br><br><hr>TechTiles Forms Automation (c) 2014-2018, All Rights Reserved</BODY></HTML>"

                if (DEBUG) 
                {
                   println "--- Message Content ---"
                   println content
                   println "--- End ---"
                }

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

def SendMail(content, action, this_sub, ccid, DEBUG)
{
try
{
	if (!content || !action || !this_sub)
	{
		println "ERROR: SendMail missing/invalid parameter"
		return false
	}
	def email = action.Parameters 
	def form = this_sub.FormName
        def sf   = form
	if (!form || form.isEmpty())
    	form = "*Unknown*"
        if (form.contains("-") && form.size() > 36)
        try
        {
            UUID uuid = UUID.fromString(form.substring(form.indexOf('-') + 1))     // If this works, then we have a UUID
            sf = form.substring(0, form.indexOf('-'))
        } catch (Exception e)
        {
           if (DEBUG) println "$form is not a dynamic flow"
        }


   def  d_email    = "info@techtiles.info",
        d_uname    = "AKIAJE66JRFQ6BJLP5OQ",
        d_password = "AnmO1nExk3Tj1eSbLnI/aakQkPLxHDAs+Fg0EhueRfFu",
        d_host     = "email-smtp.us-east-1.amazonaws.com",
     	d_port  = 587, //465,587
     	m_to = "info@techtiles.info",
        m_cc = "",
     	m_subject = "REMINDER: Please approve $sf [ID ${this_sub.ID}]"

	// Get the email parameters (such as target email ID)
	// Parameters are stored as keyword=value;keyword=value;keyword=value;...

	def p = email
	def j = 0

	if (p == null || p.isEmpty())
	{
		println "WARNING: No email contact in parameters"
		return false
	}

	String[] result = p.split(";")

	for (j=0; j < result.length; j++)
	{
		String[] this_param = result[j].split("=")
		if (this_param.length != 2)
		{
			println "WARNING: Unknown parameter " + result[j]
			continue;
		}
	
		def kwd = this_param[0]
		def val = this_param[1]
		def v = ""
	
 		if (val.startsWith("!")) 	// Output from a local command 
 		{
 			println "ERROR: Parameter with system command - not supported for reminders " + val
 			val = "NULL"
 		}
 	
 		if (val.toString().startsWith("\$")) 	// System variables
 		{
 			println "Retrieving environment variable " + val; 
 			if ((v = System.getenv(val.substring(1))) == null)
 				v = System.getProperty(val.substring(1)) 
 			if (v == null)
 				val = "NULL"
 			else
 				val = v
 		}
 		
 		if (val.toString().startsWith("_"))
 		{
 			println "ERROR: Message Properties not supported in Reminders" + val.substring(1)
 			val = "NULL"
 		}
	
		if (kwd.equalsIgnoreCase("email"))
		{
			m_to = val
			continue
		}
		
		if (kwd.equalsIgnoreCase("email2"))
		{
			m_cc = val
			continue
		}
	
		if (kwd.equalsIgnoreCase("port"))
		{
			d_port = val
			continue
		}
		
		if (kwd.equalsIgnoreCase("mailhost"))
		{
			d_host = val
			continue
		}
	
		if (kwd.equalsIgnoreCase("subject"))
		{
			m_subject = "REMINDER: $val"
			continue
		}	
	}

        // DEBUG - No email to targets

        if (DEBUG)
        {
           println "Original target = $m_to"
           m_to = "info@techtiles.info"
           if (m_cc) m_cc = "info@techtiles.info"
           if (ccid) ccid = "info@techtiles.info"
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
	if (m_cc)
           msg.addRecipient(Message.RecipientType.TO, new InternetAddress(m_cc))
        if (ccid)
           msg.addRecipient(Message.RecipientType.CC, new InternetAddress(ccid))

	Transport transport = session.getTransport("smtp");
	transport.connect(d_host, d_port.toInteger(), d_uname, d_password);
	transport.sendMessage(msg, msg.getAllRecipients());
	transport.close();
	println "REMINDER successfully sent to $m_to"
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
def ccid      = ""
def c         = 0
def total     = 0

if (!DEBUG && env["TTDEBUG"]?.contains("YES"))
{
   DEBUG = 1
   println "Debugging mode - all emails will be directed to info@techtiles.info"
}

if (args.size() < 3)
{
   println "ERROR: Missing argument - should be <tenant-id> <min-age> <max-age> <email-cc>"
}

ti  = args[0]
min = args[1]
max = args[2]
if (args.size() > 3)
   ccid = args[3]

if (!ti || !DBHOST || !DBUSER || !DBPASS)
{
  println "ERROR: Missing environment variables"
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
      println "ERROR: Can't connect to database using " + url
      System.exit(4)
   }
   if (DEBUG)
      println "Connected to $url..."

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
	println "TTreminders: No pending forms found"
        System.exit(0)
    }
	
    subs.each // Loop here with each pending form/flow in our date range 
    { 
     this_sub ->
        println "Form Submission ${this_sub.ID} created ${this_sub._CREATED} is PENDING"
	total++
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
              if (DEBUG) println "Sending reminder for $this_sub.ID / $aid"
	      if ((content = GenerateReminder(this_sub, aid, sql, DEBUG))) 
              	  if (SendMail(content, action, this_sub, ccid, DEBUG))
	   	     c++
	   }
	   else
	       println "Not pending APPROVAL or no actions found for form ${this_sub.FormName} AID $aid SEQ $hiseq"
        }
    }
	
    sql.close()
    println "TTreminders: Processed " + c + " of " + total + " reminders"
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

