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

def RunAuth(id, cmd)
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
  
   return [hrc, out, err]
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

 def groups = LDAPfield("memberOf", buffer)?.split(",")
 groups.each
 {
    if (it.startsWith("CN="))
       role += "<role>${it.substring(3)}</role>\n"
 }
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
    attrlist += "<HomePhone>$x</HomePhone>"

 return [attrlist, role]
}

// Main routine starts here... 
 
def env = System.getenv()
def id = "TTAD\\TestUser"
def pa = "TestPassword1xx"
def isauth = false
def attrlist = ""
def role = ""

     // We support three methods: PAM, LDAP and WINBIND 
     // PAM can be configured to work with just about anything

     try 
     {
       def rc = 0
       def out = ""
       def err = ""

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
             break
          }

          // User name can be: simple user name, user@domain or domain (backslash) user

          def duser = id
          int i = 0
          if ((i = id.indexOf("@")) > 0)
             duser = id.substring(i+1)
          if ((i = id.indexOf("\\")) > 0)
             duser = id.substring(i+1)

          def ldapcmd = "ldapsearch -LLL -x -H ldap://${env["AUTH_HOST"]} -D $id " + 
                        " -w $pa -b ${env["AUTH_DN"]} (samaccountname=$duser)"
          // def ldapcmd = "../bin/ADAUTH.sh $id $duser $pa"
          println "Command is $ldapcmd"
          (rc, out, err) = RunAuth(id, ldapcmd)
          if (!rc)
          {
             isauth = true
             (attrlist, role) = GetLDAPInfo(id, out.toString())
          }
          else
          {
             isauth = false
             println "Login: ERROR - invalid user/password for $id"
          }
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
       }
    }
    catch (Exception e)
    {
       println "Login: error running authentication process - " + e.toString()
    }
    
    println "---ATTRLIST---"
    println attrlist
    println "---ROLES---"
    println role
    
    System.exit(0)

   
