
// TTcal: A simple script to build and send calendar items via email
//
// History:
//
// 2020-05-05: New [VRR]
//
// Copyright (c) 2020 - TechTiles LLC. All Rights Reserved.

import javax.mail.*
import javax.mail.internet.*
import groovy.text.SimpleTemplateEngine
import org.apache.log4j.Logger
import org.apache.log4j.Level
import groovy.util.logging.Log4j
import groovy.sql.Sql

public class SMTPAuthenticator extends Authenticator
{
  public PasswordAuthentication getPasswordAuthentication()
  {
     // This is used when connecting as the Sender
     return new PasswordAuthentication('info%40techtiles.net', 'irk4j8xibC!9');
  }
}

def Shell(cmd, log)
{
  def out = ""
  def process = new ProcessBuilder([ "/bin/bash", "-c", cmd.toString()])
                                    .directory(new File("/tmp"))
                                    .redirectErrorStream(true)
  p = process.start()
  p.outputStream.close()
  p.inputStream.eachLine
  { line ->
     out += line + "\n"
  }
  p.waitFor()
  if (p.exitValue() > 0)
     log.warn "Shell: WARNING - RC ${p.exitValue()} for $cmd"
  log.info "Shell: $cmd [${p.exitValue()}] - ${out?.trim()}"
  return [p.exitValue(), out?.trim()]
}

def ParseCommand()
{
 def cli = new CliBuilder(usage:'TTcal [options] ', header: 'options:')

 Logger log = Logger.getInstance(getClass())
 log.info "Parse: ARGS ${args.join(' ')}"

 cli.h(longOpt:'help',                                          'About TTcal')
 cli.D(longOpt:'debug',                                         'Enable debug-level message logging')
 cli.to(longOpt:'to',           args:1, argName:'to',           'Target email address list')
 cli.s(longOpt:'summary',       args:1, argName:'summ',         'Event summary for calendar item')
 cli.t(longOpt:'title',         args:1, argName:'title',        'Event title for calendar item')
 cli.w(longOpt:'when',          args:1, argName:'when',         'Event time and date')
 cli.e(longOpt:'ends',          args:1, argName:'ends',         'Event duration')
 cli.m(longOpt:'msg',           args:1, argName:'msg',          'Message description text')
 cli.mt(longOpt:'msgTemplate',  args:1, argName:'msgTemplate',  'Message description template file')
 cli.d(longOpt:'desc',          args:1, argName:'desc',         'Event description text')
 cli.dt(longOpt:'descTemplate', args:1, argName:'descTemplate', 'Event description template file')
 cli.o(longOpt:'organizer',     args:1, argName:'organizer',    'Organizer email address')
 cli.l(longOpt:'location',      args:1, argName:'location',     'Event location')
 cli.f(longOpt:'form',          args:1, argName:'form',         'Form identifier')

 def options = cli.parse(args)

 if (!options || !args)
 {
    println "Invalid options - exiting"
    log.warn("Parse: Can't parse args ${args.join(' ')}")
    System.exit(4)
 }

 if (options.D || log.level == Level.DEBUG)
 {
    log.level = Level.DEBUG
    log.debug "Parse: options.D  = $options.D"
    log.debug "Parse: options.to = $options.to"
    log.debug "Parse: options.t  = $options.t"
    log.debug "Parse: options.s  = $options.s"
    log.debug "Parse: options.w  = $options.w"
    log.debug "Parse: options.e  = $options.e"
    log.debug "Parse: options.d  = $options.d"
    log.debug "Parse: options.dt = $options.dt"
    log.debug "Parse: options.m  = $options.m"
    log.debug "Parse: options.mt = $options.mt"
    log.debug "Parse: options.o  = $options.o"
    log.debug "Parse: options.l  = $options.l"
    log.debug "Parse: options.f  = $options.f"
 }

 if (options.help)
 {
    cli.usage()
    println "\n"
    println "The TO and WHEN parameters are required.\n"
    println "If not specified, the ORGANIZER defaults to the first address from the TO parameter\n"
    println "WHEN accepts a wide range of friendly timestamps or relative values:"
    println "   - 12/25/2020 at 11:00" 
    println "   - next tuesday"
    println "   - tomorrow + 1 hour"
    println " "
    println "ENDS can be a specific date or relative to WHEN:"
    println "   - 1 hour (event starts at WHEN and ends in one hour)"
    println "   - 12/25/2020 at midnight (event ends at a specific time)"
    println " "
    println "There are two parts to the calendar item: a 'message' and a 'calendar item', " + 
            "and each of these sections with titles and descriptive text. " +     
            "The MSG/MSGT control the text body of the email message that receivers see. It can be HTML or plain text. " +     
            "The DESC/DESCT control the description in the calandar item itself. It must be plain text. "
    println "The titles are set by the TITLE (for the email message) and SUMM (for the calendar item) parameters. " 
 
    System.exit(0) 
 }

 if (!options.to || !options.w) 

 {
    println "ERROR: Missing required options --to and --when"
    System.exit(4)
 }

 log.debug "Parse: Complete - no errors" 

 return options
}

def GetForm(formid, vars, log)
{
def env      = System.getenv()
def DBHOST   = env["TTDB"]
def DBPORT   = env["TTDBPORT"]
def DBUSER   = env["TTDBUSER"]
def DBPASS   = env["TTDBPASS"]
def DBSCHEMA = "Forms"
def ti       = (env["TENANTID"] ? env["TENANTID"] : env["TENANT"])

Sql sql      = null

 log.info "GetForm: Form $formid using T:$ti H:$DBHOST:$DBPORT U:$DBUSER P:$DBPASS"
  
 if (!ti || !DBHOST || !DBUSER || !DBPASS)
 {
   println "ERROR: Missing environment variables"
   log.warn "GetForm: missing environment variables" 
   System.exit(4)
 }

 try
 {
   // Initialize SQL connection to the default database

   sql = new Sql()
   url = "jdbc:mysql://" + DBHOST + ":" + DBPORT + "/" + DBSCHEMA
   sql = Sql.newInstance(url, DBUSER, DBPASS, "com.mysql.jdbc.Driver")
   if (!sql)
   {
      log.warn "GetForm: Can't connect to database using " + url
      println  "ERROR: Can't connect to database "
      System.exit(4)
   }

   log.info "GetForm: Connected to $url"

   // Add the form data to the variables
   
   def t = sql.firstRow("SELECT * FROM CEDS_extensions.Tenants WHERE ID = '" + ti + "'")
   if (!t)
   {
	sql.close()
        log.warn "GetForm: Tenant $ti not found"
	println "ERROR: Tenant information not found for tenant $ti"
        System.exit(4)
   }
   vars.put("TENANT", ti.toString())
   vars.put("TENANTNAME", t.Description.toString())
   vars.put("TENANTEMAIL", t.ContactEmail.toString())

   log.info "GetForm: Tenant ${t.ID.toString()}:  ${t.Description.toString()} Contact: ${t.ContactEmail.toString()}" 

   def f = sql.firstRow("SELECT * FROM FormSubmissions WHERE _TENANT = '" + ti + "' AND ID = '" + formid + "'")
   if (!f)
   {
	sql.close()
        log.warn "GetForm: Form $formid in tenant $ti not found"
	println "ERROR: Form $formid not found"
        System.exit(4)
   }
   vars.put("FORMNAME",    f.FormName)
   vars.put("FORMCREATED", f._CREATED)
   vars.put("FORMDOCID",   f.DocID)
   vars.put("FORMSIGNED", (f.Signature ? "true" : "false"))
   log.info "GetForm: Form $formid is ${f.FormName} created ${f._CREATED} DocID ${f.DocID} Signed ${(f.Signature ? "Yes": "No")}" 

   // Pass the form varialbes too 

   def fvar = [:]
   def xml = new String(f.content)
   if (!xml || xml.isEmpty())
   {
      sql.close()
      println "ERROR: No form data present"
      log.warn "GetForm: No form XML"
      System.exit(4) 
    }

    def root = new XmlParser().parseText(xml)
    if (!root || !root.name().equalsIgnoreCase("form"))
    {
       sql.close()
       println "ERROR: Unknown form type for form $formid" 
       log.warn "GetForm: Form $formid has root ${root.name}, not 'form'"
       System.exit(4)
    }

    root.depthFirst().each
    {
       if (it.children()?.size() > 1 || !it.text() || !it.value()?.getClass()?.toString().contains("NodeList") || it.attributes())
          log.debug "GetForm: XML Node ${it.name()} skipped ${it.value()?.getClass()?.toString()} ${(it.attributes ? "with attributes" : "")}"
       else
       {
          log.debug "GetForm: ${it.name()} = ${it.text()}"
          fvar.put(it.name(), it.text())
       }
    }
    
    vars.put("FORM", fvar)

    log.debug "GetForm: SUCCESS" 

    sql.close()
    return true 
} // try...

catch (Exception e)
{
	println "ERROR: Exception during form lookup"
	log.warn "GetForm: Exception ${e.toString()}"
        log.warn e.printStackTrace()
        if (sql) sql.close()
	return false
}

// All finished  

   return true
}


def Opt2Var(options, log)
{
 def vars = [:]
 def to1  = ""     

 // First, we get the form database info 

 if (options.f)  // Form ID - fetch the form itself
 {
    vars.put("FORMID", options.f?.toString().trim())
    if (!GetForm(vars['FORMID'], vars, log))
    {
       log.warn "O2V: Can't fetch form ${options.f}"
       println "ERROR: Can't fetch form ${options.f}"
       System.exit(4)
    }
 }

 // Here we build the var map 

 if (options.to) // Target address (can be a list)
 {
    vars.put("TO", options.to)
    def attendees = ""
    options.to.tokenize(',; ').each
    {
       if (attendees)
          attendees += "\n"
       else
          to1=it
       attendees += "ATTENDEE;RSVP=FALSE;TYPE=INDIVIDUAL;CN=:Mailto:$it"
    }
    vars.put("ATTENDEES", attendees)
 }

 if (options.o)  // Meeting Organizer
    vars.put("ORGANIZER", options.o) 
 else
    vars.put("ORGANIZER", to1)

 if (options.l)  // Meeting Location
    vars.put("LOCATION", options.l) 
 else
    vars.put("LOCATION", "None")

 if (options.s)  // Meeting subject or summary
    vars.put("SUMMARY", options.s)
 else
    vars.put("SUMMARY", "Event: ${vars['FORMNAME']} [ID ${vars['FORMID']}, ${vars['FORMCREATED']}]")

 if (options.t)  // Email title
    vars.put("TITLE", options.t)
 else
    vars.put("TITLE", vars['SUMMARY'])

 if (options.w)  // When (start date) 
 { 
    def cmd = "/bin/date -d \"${options.w}\" \"+%Y%m%dT%H%M00\" "
    def (rc, date) = Shell(cmd, log)
    if (rc > 0) 
    {
       log.warn "O2V: Invalid 'WHEN' date - $out"
       println "Invalid option for start date: $date"
       System.exit(4)
    } 
    vars.put("WHEN", "TZID=America/New_York:" + date.trim())
 }

 if (options.e)  // Duration (end date) - relative to start 
 {
    def cmd = "/bin/date -d "
    if (options.w)
       cmd += "\"${options.w} + ${options.e} \" \"+%Y%m%dT%H%M00\" "
    else
       cmd += "\"now + ${options.e}\" \"+%Y%M%dT%H%M00\" "
    def (rc, date) = Shell(cmd, log)
    if (rc > 0) 
    {
       log.warn "O2V: Invalid 'ENDS' date - $date"
       println "Invalid option for end date: $date"
       System.exit(4)
    } 
    vars.put("ENDS", "TZID=America/New_York:" + date.trim())
 }
 else
 {
    log.warn "O2V: Warning - no '-e' option...using default of 1 hour" 
    def cmd = "/bin/date -d "
    if (options.w)
       cmd += "\"${options.w} + 1 hour\" \"+%Y%m%dT%H%M00\" "
    else
       cmd += "\"now + 1 hour\" \"+%Y%M%dT%H%M00\" "
    def (rc, date) = Shell(cmd, log)
    if (rc > 0) 
    {
       log.warn "O2V: Invalid 'ENDS' date - $date"
       println "Invalid option for end date: $date"
       System.exit(4)
    } 
    vars.put("ENDS", "TZID=America/New_York:" + date.trim())
 }

 if (options.d)  // Description - body of cal item
    vars.put("DESC", options.d?.toString().trim().replace("\n", "\\n"))
 else
    if (options.dt) // This is description from a template file 
       vars.put("DESC", GenerateContent(options.dt, vars, log).trim().replace("\n", "\\n"))

 if (options.m)  // Message - body of email 
    vars.put("MESSAGE", options.m?.toString().trim())
 else
    if (options.mt) // This is a message in a template file 
       vars.put("MESSAGE", GenerateContent(options.mt, vars, log).trim())
    else
       vars.put("MESSAGE", "This event was generated by a workflow task")

 log.debug "O2V: Complete - no errors" 

 return vars
}

def GenerateContent(script, vars, log)
{
   // We use the Groovy Template Engine to expand a skeleton into an iCalendar  

 try
 {
   def te = new SimpleTemplateEngine()
   def td = new File(script).text
   def content = te.createTemplate(td).make(vars).toString()

   if (!content)
   {
      println "ERROR: Can't resolve template $script"
      log.warn "Gen: Can't resolve template $script" 
      System.exit(4)
   }

   log.debug "Content: --- $script START ---"
   content?.trim().eachLine() { log.debug "             $it" }
   log.debug "Content: --- $script END ---"
 
   return content?.trim() 
 }
 catch (Exception e)
 {
   log.warn "Content: Can't resolve template $script - ${e.toString()}"
   log.warn e.printStackTrace()
   println "ERROR: Can't resolve template $script - ${e.toString()}" 
   System.exit(4)
 }
}

def SendMail(content, cal, email, vars, log)
{
 try
 {
   if (!content || !cal || !email)
   {
     println "ERROR: SendMail missing/invalid parameter"
     log.warn "SendMail: Missing parameters"
     return false
   }

   def  d_email    = "info@techtiles.info",
        d_uname    = "AKIAJE66JRFQ6BJLP5OQ",
        d_password = "AnmO1nExk3Tj1eSbLnI/aakQkPLxHDAs+Fg0EhueRfFu",
        d_host     = "email-smtp.us-east-1.amazonaws.com",
        d_port  = 587, //465,587
        m_to = "",
        m_cc = "",
        m_subject = vars['TITLE']

  if (!m_subject)
     m_subject = "TechTiles: Calendar Task"
		
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
  
  MimeBodyPart filePart = new MimeBodyPart()
  filePart.setContent(cal, "text/calendar")
  mp.addBodyPart(filePart)

  def auth = new SMTPAuthenticator()
  def session = Session.getInstance(props, auth)
//session.setDebug(true)

  def msg = new MimeMessage(session)
  msg.setContent(mp)
  msg.setSubject(m_subject)
  msg.setFrom(new InternetAddress(d_email))

  vars['TO'].tokenize(",; ").each 
  {
    log.debug "Send: TO $it"
    msg.addRecipient(Message.RecipientType.TO, new InternetAddress(it))
  }

  Transport transport = session.getTransport("smtp");
  transport.connect(d_host, d_port.toInteger(), d_uname, d_password);
  transport.sendMessage(msg, msg.getAllRecipients());
  transport.close();
  log.info "Send: Calendar item sent to ${vars['TO']}"
  return true
}

catch (Exception e)
{
  println "---Exception sending email---"
  println e.toString()
  println e.printStackTrace()
  return false
}
}

// START: Main script is here...

def now       = new Date().format("dd-MMM-yyyy")
def env       = System.getenv()
def msgfile   = "./msg.txt"
def script    = "./template.ical"

Logger log = Logger.getInstance(getClass())

try
{
   log.level = Level.INFO
   log.info 'TTcal: STARTING on Groovy: ' + GroovySystem.version + " JVM: " + System.getProperty("java.version")

   def opts = ParseCommand()
   if (!opts)
   {
      log.warn 'TTcal: ENDING - missing/invalid args'
      System.exit(4)
   }

   def vars = Opt2Var(opts, log)
   if (!vars)
   {
      log.warn "TTcal: ENDING - incorrect options"
      System.exit(4)
   }

   // Add a few other things to the var list

   vars.put("UID", new Date().format("yyyyMMdd'T'HHmmss.SSS") + 
            "-" + Math.random().toString().substring(2,5) +
            "@" + InetAddress.getLocalHost().getHostName())
   def cmd = "/bin/date -d now \"+%Y%m%dT%H%M00Z\" "
   def (rc, date) = Shell(cmd, log)
   if (rc > 0) 
   {
      log.warn "ERROR: Can't generate timestamp - $date"
      println "ERROR: Can't generate timestamp: $date"
      System.exit(4)
   } 
   vars.put("TIMESTAMP", date.trim())
 
   log.debug "---Variables START---"
   vars.each { log.debug "   ${it.key} = ${it.value}" }
   log.debug "---Variables END---"

   def cal     = GenerateContent(script, vars, log) 
   SendMail(vars['MESSAGE'], cal, vars['TO'], vars, log) 
   System.exit(0)
} // try...

catch (Exception e)
{

	println "---ERROR---"
	println e.toString()
        println e.printStackTrace()
	log.warn"---ERROR---"
	log.warn e.toString()
        log.warn e.printStackTrace()
        
	return "Exception during processing \n" + e
}

// All finished  

return "Okay"

