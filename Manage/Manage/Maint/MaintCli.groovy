import groovy.sql.Sql
import groovy.io.FileType
import java.sql.Connection
import java.sql.Statement
import java.sql.SQLException
import java.sql.SQLWarning
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
import java.nio.charset.Charset
import org.apache.commons.io.FilenameUtils

class Globals
{
  static String  mwork     = ""		// Work directory
  static String  msource   = ""		// Default source
  static String  mbkup     = ""		// Default backup
  static String  tid       = ""		// Tenant ID string
  static String  pid       = ""		// Patch ID string
  static String  user      = ""		// OS username
  static String  uid       = ""		// OS user ID
  static Integer isMulti   = 0 		// Running on multi-tenant device? 
  static Integer isRestart = 0 		// Reboot at end? 
  static Integer isReboot  = 0 		// Reboot at end? 
  static List    tlist     = []		// List of active tenants on device
  static List    msgs      = []		// Output messages (unused)
}

// These are the items passed to the condition evaluator

class params
{
  def env   = []
  def patch = []
  def tenant= ""
  def user  = ""
  def uid   = 0
}

def Shell(cmd, msgs) 
{
  def out = ""
  def process = new ProcessBuilder([ "/bin/bash", "-c", cmd.toString()])
                                    .directory(new File("/tmp"))
                                    .redirectErrorStream(true) 
  def env = process.environment()
  if (Globals.mwork)
     env.put("MAINT_WORK",  Globals.mwork)
  if (Globals.msource)
     env.put("MAINT_SOURCE", Globals.msource)
  if (Globals.mbkup)
     env.put("MAINT_BACKUP", Globals.mbkup)
  if (Globals.pid)
     env.put("MAINT_PATCHID", Globals.pid)

  p = process.start()
  p.outputStream.close()
  p.inputStream.eachLine 
  { line -> 
     out += line + "\n"
  }
  p.waitFor()
  if (p.exitValue() > 0)
     Message(msgs, "Maint: WARNING - RC ${p.exitValue()} for $cmd")
  return [p.exitValue(), out]
}

def Resolve(var, msgs)
{
   def env = []
   System.getenv().each
   { li ->
       env.add(li)
   }
   
   def cmd = 'echo "' + var + '"'
   def (rc, out) = Shell(cmd, msgs)
   
   if (rc || !out)
   {
      Message(msgs, "Maint: ERROR - can't resolve $var")
      Message(msgs, "Maint: Command is $cmd")
      Message(msgs, "Maint: ${out.toString().trim()}")
   }
   return out?.toString().trim()
}

def MD5(str)
{
   MessageDigest md = MessageDigest.getInstance("MD5")
   byte[] array = md.digest(str.getBytes())
   String result = ""
   for (final byte element : array) 
   {
      result += Integer.toHexString(element & 0xFF | 0x100).substring(1, 3)
   }
 
   return result.toString()
}

def RunScript(p, msgs)
{
def command = p.Source

   if (!p.Source)
   {
      Message(msgs, "Maint: ERROR - no script command found")
      return false
   }
   if (p.Target) 
      command = "${p.Source} ${p.Target}"
   Message(msgs, "Maint: Script is $command")
   command = Resolve(command.trim(), msgs)
   Message(msgs, "Maint: Script resolves to $command")
   def (rc, out) = Shell(command, msgs)
   if (rc)
      Message(msgs, "Maint: Script fails, RC = $rc - $out")
   else
      Message(msgs, "Maint: Script succeeds - $out")
   
   if (rc) 
      return false
   
   return true
}

def CopyFile(p, msgs)
{
   if (!p.Target || !p.Source)
   {  
      Message(msgs, "Maint: ERROR - no source or target for copy operation")
      return false
   }
   Message(msgs, "Maint: Copy ${p.Source} -> ${p.Target}")
   def input = Resolve(p.Source, msgs)
   def output = Resolve(p.Target, msgs)
   if (!input.equals(p.Source))
      Message(msgs, "Maint: Source resolves to $input")
   if (!output.equals(p.Target))
      Message(msgs, "Maint: Target resolves to $output")

   // Check the source file 

   def sf = new File(input)
   if (!sf.exists()) 
   {
      Message(msgs, "Maint: ERROR - source file ${input} doesn't exist")
      return false
   }
   if (!sf.canRead())
   {
      Message(msgs, "Maint: ERROR - source file ${input} not readable")
      return false
   }
   def sfMD5 = MD5(sf)
   Message(msgs, "Maint: Source exists, modified " + new Date(sf.lastModified()).format('EEE MMM dd hh:mm:ss a yyyy'))
   Message(msgs, "Maint: Source file size currently ${sf.length()} bytes")
   Message(msgs, "Maint: Source file MD5 currently " + sfMD5)

   // Check the target too 

   def tf = new File(output)
   if (tf.exists())
   {
      def tfMD5 = MD5(tf)
      Message(msgs, "Maint: Target exists, modified " + new Date(tf.lastModified()).format('EEE MMM dd hh:mm:ss a yyyy'))
      Message(msgs, "Maint: Target file size currently ${tf.length()} bytes")
      Message(msgs, "Maint: Target file MD5 currently " + tfMD5)
      if (sfMD5.equals(tfMD5))
      {
         Message(msgs, "Maint: Signatures match - file already updated")
         return true      // Causes logging to occur
      }
      if (!tf.canWrite())
      {
         Message(msgs, "Maint: ERROR - target file not writable")
         return false
      }
      if (tf.lastModified() > sf.lastModified())
      {
         if (p.CopyNewer)
            Message(msgs, "Maint: WARNING - target newer than source, but CopyNewer option set")
         else
         {
            Message(msgs, "Maint: ERROR - target newer than source, and CopyNewer option not set")
            return false
         } 
      }
      def bkf = "\$MAINT_BACKUP/${FilenameUtils.getName(output)}.Patch${String.format("%03d", p.ID)}@${new Date().format("yyyy-MM-dd'T'HH:mm:ss.S")}.gz"
      bkf = Resolve(bkf, msgs)
      def (rc, out) = Shell("gzip -c $output > $bkf", msgs)
      if (rc)
      {
         Message(msgs, "Maint: ERROR - can't create backup file in $bkf")
         return false
      }
      Message(msgs, "Maint: Target $output backed up to $bkf")
   }

   // Finally we can copy the file 

   (rc, out) = Shell("cp -p $input $output", msgs) 
   if (rc)
   {
      Message(msgs, "Maint: ERROR - cp returns $rc")
      Message(msgs, "Maint: $out")
      return false
   }
   Message(msgs, "Maint: Copied file successfully, operation complete") 
   return true 
}

def doPatch(p, msgs)
{

   if (!p || !p?.ID)
   {
      Message(msgs, "Maint: ERROR - Missing Parameters")
      return false
   }

   Globals.pid = p.toString()

   if (p.Desc)
   {
      Message(msgs, "Maint: ---Description---")
      Message(msgs, p.Desc)
      Message(msgs, "Maint: ---Description---")
   }

   switch (p.Type)
   {
   case '0':       // Copy a file
      return CopyFile(p, msgs)
      break

   case '1':       // Run a script
      return RunScript(p, msgs)
      break
   
   default:        // Default - unknown option
      Message(msgs, "Maint: ERROR - unknown action type ${p.Type}") 
      return false 
   }

   return true
}

def CheckConditions(p, msgs)
{
   if (!p.Conditions || !p.Conditions?.trim())
      return true
 
   Message(msgs, "Maint: Checking condition ${p.Conditions}")
  
   def vars = new params()
   vars.env = System.getenv()
   vars.patch = p 
   vars.tenant= Globals.tid 
   vars.user  = Globals.user 
   vars.uid   = Globals.uid 
   def rc = Eval.me("args", vars, p.Conditions) 
   Message(msgs, "Maint: Condition expression returns $rc")
   
   return rc
}     

def IsMulti()
{
 Globals.isMulti = 0

 def f = new File("/etc/TechTiles/Appliance") 
 
 if (!f.exists() || !f.getText().contains("MULTI"))
    return 0        

 // Get the list of active tenants on this device 

 def tdir = new File('/secure')
 if (!tdir)
 {
    println "Can't open /secure"
    return 0 
 }

 tdir.eachFile(FileType.DIRECTORIES) 
 {
    if (it?.name?.contains("tenant."))
       Globals.tlist << it.name.tokenize(".")[1]
 }
 Globals.tlist?.sort()
 Globals.isMulti = 1   
 return 1
}

def Message(msglist, line)
{
  msglist.add(line + "\n")
  println(line)
  return
}

def LogResult(p, tid, msgs, retcode, sql)
{

 // We skip any logging if the tenant ID is zero (new builds)

 if (!tid || tid.equals("00000"))
 {
    println "Maint: Ignore log tenant 0/new builds"
    return 
 }

 // Get host name/type/serial number

 def dev = "*UNKNOWN*"
 def h = ""
 try
 { 
    h = InetAddress.getLocalHost()?.getHostName()?.trim()
 } 
 catch (Exception eh1)
 {
    try 
    {
        h = InetAddress.getHost()?.getHostname()?.trim()
    } 
    catch (Exception eh2)
    {
        h = new File("/etc/hostname")?.text.trim()
    }
 }

 if (h)
    dev = h

 if (Globals.uid.equals("0"))
 {
    def (rc, out) = Shell("dmidecode -s baseboard-product-name", Globals.msgs) 
    if (!rc && out?.toString().trim())  
       dev += "/" + out.toString().trim() 
    (rc, out) = Shell("dmidecode -s baseboard-serial-number", Globals.msgs)
    if (!rc && out?.toString().trim())
       dev += "/" + out.toString().trim() 
    def fid = new File("/sys/hypervisor/uuid") 
    if (fid && fid.exists())
       dev += "/VM-" + fid.getText()?.trim()
 } 
 else
 {
    def fty = new File(Resolve("\$FMA_DIR/conf/SystemType", msgs))
    if (fty && fty.exists())
       dev += "/" + fty.getText()?.trim()
    else
       println "Maint: Can't read SystemType"

    def fid = new File(Resolve("\$FMA_DIR/conf/SystemID", msgs))
    if (fid && fid.exists())
       dev += "/" + fix.getText()?.trim()
    else
       println "Maint: Can't read SystemID"
 }

 def m = ""
 msgs.each { m += it }
 

 def insrc = sql.executeInsert(
             "INSERT INTO PatchStatus " + 
             "(PatchID, Tenant, Device, PatchStatus, Result, Output) " +
             "VALUES (?, ?, ?, ?, ?, ?)", 
             [p.ID, tid, dev, p.status, retcode, m?.toString()?.trim()?.getBytes()])

 def updrc = 0
 if (!retcode)
    updrc = sql.executeUpdate("UPDATE Patches SET Count = Count +1, Errors = Errors + 1 WHERE ID=${p.ID}")
 else
    updrc = sql.executeUpdate("UPDATE Patches SET Count = Count +1 WHERE ID=${p.ID}")
 
 println "Maint: Patch Log updated at record ${insrc[0][0]} with ${updrc} updates"

 return 
}

def GetUserInfo()
{
 def env = System.getenv()

 if (env['USER'])
    Globals.user = env['USER']
 else
    if (env['USERNAME'])
       Globals.user = env['USERNAME']
    else
       if (System.getProperty("user.name"))
          Globals.user = System.getProperty("user.name")
       else
       {
          def (rc, out) = Shell("whoami", Globals.msgs)
          if (!rc)
             Globals.user = out.toString().trim()
          else
             Globals.user = "*UNKNOWN*"
       }

 if (env['EUID'])
    Globals.uid = env['EUID']
 else
    if (env['UID'])
       Globals.uid = env['EUID']
    else
    {
       def (rc, out) = Shell("id -u", Globals.msgs)
       if (!rc)
          Globals.uid = out.toString().trim()
       else
          Globals.uid = "?"
    }
}


// Load the desired JDBC driver 

jd = new File("/appliance/jms/lib/mysqlconnector-java-5.1.44-bin.jar")
if (jd.exists())
   this.class.classLoader.rootLoader.addURL( new URL("file:///appliance/jms/lib/mysql-connector-java-5.1.44-bin.jar"))
else
   this.class.classLoader.rootLoader.addURL( new URL("file:///appliance/jms/lib/mysql-connector-java-5.1.26-bin.jar"))

def count  = 0
def errs   = 0
def skips  = 0
def reboot = false
def restart= false
def msgs   = []
def env    = System.getenv()
def tid    = (env['TENANT'] ? env['TENANT'] : "")
def dbhost = (env['MAINT_DBHOST'] ? env['MAINT_DBHOST'] : "localhost")
def dbport = (env['MAINT_DBPORT'] ? env['MAINT_DBPORT'] : "21000" )
def dbuser = "maint"
def dbpass = "Smp4@dmv"
def dbdrvr = "com.mysql.jdbc.Driver"
def url    = "jdbc:mysql://" + dbhost + ":" + dbport + "/Management" 
def pt     = ""
def mt     = IsMulti()

Sql sql = new Sql()

 try 
 {
     GetUserInfo()
     println "Maint: Running as User ${Globals.user}, UID ${Globals.uid}"
 
     if (!env['MAINT_WORK'])
     {
        def (rc, pwd) = Shell("echo \$PWD", msgs)
        pwd = pwd?.trim()
        if (!pwd?.trim())
           pwd = "/tmp"
        println "Maint: MAINT_WORK unset - defaults to $pwd"
        Globals.mwork = pwd
    }
    if (!env['MAINT_SOURCE'])
    {
       def msrc = "/mnt/shared"
       def f = new File(msrc)
       if (!f.exists())
          msrc = "/shared"
       println "Maint: MAINT_SOURCE unset - defaults to $msrc"
       Globals.msource = msrc
    }
    if (!env['MAINT_BACKUP'])
    {
       def mbkup = "/appliance/backup/Maint"
       def f = new File(mbkup)
       if (!f.exists())
          Shell("mkdir -p $mbkup", msgs) 
       println "Maint: MAINT_BACKUP unset - defaults to $mbkup"
       Globals.mbkup = mbkup
    }

    if (mt)
    {
       println "Maint: Current system is multi-tenant" 
       tid = InetAddress.getLocalHost()?.getHostName()?.trim()
       if (tid?.toString().contains('.'))
          tid = tid.tokenize(".")[0]
       println "Maint: Using multi-tenant ID $tid" 
    }
    else
       if (args && args[0])
       {
          println "Maint: Reset tenant ID to ${args[0]} vs $tid" 
          tid = args[0] 
       }

    if (!tid)
    {
       println "Maint: ERROR - No Tenant ID"
       System.exit(4)
    }
    Globals.tid = tid

    println "Maint: Connecting to $url..." 
    sql = Sql.newInstance(url, dbuser, dbpass, dbdrvr)
    if (!sql)
    {
       println "Maint: ERROR - Can't connect to database using " + url
       System.exit(8)
    }

    println "Maint: *DB Connected*"  

    // Show how many patches defined and applied

    def r = sql.firstRow('SELECT COUNT(*) AS Total FROM Patches WHERE Recur = "NO"')
    println "Maint: Total of ${r.Total} non-recurring patches defined"
    r = sql.firstRow('SELECT COUNT(*) AS Total FROM Patches WHERE Recur != "NO"')
    println "Maint: Total of ${r.Total} recurring patches defined"
    r = sql.firstRow('SELECT COUNT(*) as Total FROM PatchStatus WHERE Tenant = "' + tid + '"')
    println "Maint: Total of ${r.Total} patches installed for tenant $tid"

    // Now get the worklist - two queries give us non-recurring and patches

    def q = 'SELECT * FROM Patches P WHERE P.Status = "OK" AND P.Recur = "NO" AND NOT EXISTS ' +  
            '(SELECT * FROM PatchStatus as S WHERE P.ID = S.PatchID AND S.Tenant = "' + tid + '" AND S.Result > 0) ' +
            "ORDER BY P.Package, P.ID"    
    def plist = sql.rows(q)
 
    q = 'SELECT * FROM Patches P WHERE P.Status = "OK" AND P.Recur != "NO" AND ' + 
        '(NOT EXISTS (SELECT * FROM PatchStatus as S WHERE P.ID = S.PatchID AND S.Tenant = "' + tid + '" AND S.Result > 0) OR ' +  
        ' P.Recur < DATEDIFF(CURRENT_TIMESTAMP, (SELECT MAX(Runtime) FROM PatchStatus as S WHERE P.ID = S.PatchID AND S.Tenant = "' + tid + '")))' 
    def rlist = sql.rows(q)

    if (!plist && !rlist)
    {
       println "Maint: No work to do - system is up to date"
       sql.close()
       System.exit(0)
    }
    println "Maint: Attempting to apply ${plist.size()} one-time patches and ${rlist.size()} recurring patches"
 
    plist = plist + rlist 

    plist.each
    { p ->
       msgs = []
       Globals.pid = ""
       if (p.Recur.contains("NO"))
          pt = ""
       else
          pt = "(recurring)"
       Message(msgs, "Maint: START Patch #${p.ID} created ${p.Created} updated ${p.Changed} Type ${p.Type} $pt")
       Message(msgs, "Maint: Installed on ${p.Count} systems, failed on ${p.Errors}")
       if (p.Package)
          Message(msgs, "Maint: Part of package ${p.Package}")
 
       // Handle the multi-tenant options
       
       if (mt)
       {
          switch (p.PatchMT)
          {
           case 0:	// 0 = not installable on multitenant 
              Message(msgs, "Maint: STOP - Patch #${p.ID} not installable on multi-tenant appliances")
              skips++
              return
           
           case 1:	// 1 = install only into "base" directory
              Message(msgs, "Maint: Patch #${p.ID} installed into base multi-tenant directories only")
              break 
           
           case 2:      // 2 = install into EACH active tenant 
              Message(msgs, "Maint: Patch #${p.ID} requires separate install into each tenant")
              Globals.tlist.each
              { tenant-> 
                def mtid = tid
                tid = tenant
                if (CheckConditions(p, msgs))
                {
                   rc = doPatch(p, msgs)
                   if (rc) 
                   {
                      Message(msgs, "Maint: IsReboot: ${p.isReboot}, IsRestart: ${p.isRestart}")
                      if (p.isReboot)
                         reboot = true
                      if (p.isRestart)
                         restart = true
                   }
                   else
                      errs++
                   LogResult(p, tid, msgs, rc, sql) 
               }
               else
               {
                  Message(msgs, "Maint: Patch bypassed due to unmet condition")
                  skips++
               }
               Message(msgs, "Maint: STOP Patch #${p.ID}")
               count++ 
               tid = mtid 
              }
              return
           
           default: 
              Message(msgs, "Maint: STOP - Patch #${p.ID} invalid multitenant option at ${p.PatchMT}")
              skips++
              return
          }
       } 

       if (CheckConditions(p, msgs))
       {
          rc = doPatch(p, msgs)
          if (rc) 
          {
              Message(msgs, "Maint: IsReboot: ${p.isReboot}, IsRestart: ${p.isRestart}")
              if (p.isReboot)
                 reboot = true
              if (p.isRestart)
                 restart = true
          }
          else
             errs++
          LogResult(p, tid, msgs, rc, sql) 
       }
       else
       {
          Message(msgs, "Maint: Patch bypassed due to unmet condition")
          skips++
       }
       Message(msgs, "Maint: STOP Patch #${p.ID}")
       count++
    }

    sql.close()

    println "Maint: Complete - skipped $skips patches, processed $count patches with $errs errors"
    
    // Todo: handle multitenant differently

    if (reboot) 
    {
       println "Maint: Reboot will be scheduled"
       def (rc, out) = Shell("at -f $FMA_DIR/bin/Reboot.sh +1 minute", msgs)
    }
    else
       if (restart)
       {
          println "Maint: Restart will be scheduled"
          def (rc, out) = Shell("$FMA_DIR/bin/JMSrestart ALL", msgs)
       }
       else
          println "Maint: No reboot or restart required"

    return
 }
 catch (Exception e)
 {
    println "***EXCEPTION**"
    println e.toString()
    println e.printStackTrace()

    if (sql)
       sql.close()
 
    if (tid)
    {
       def XF = new File("/mnt/shared/Manage/Maint/EXCEPTIONS")
       def XS = new File("/shared/Manage/Maint/EXCEPTIONS")
       if (XF.exists())
       { 
          XF << "\n***EXCEPTION in Tenant $tid***\n"  
          XF << "\n" + new Date().format('EEE MMM dd hh:mm:ss a yyyy') + "\n" 
          XF << e.toString() + "\n"
       }
       if (XS.exists())
       { 
          XS << "\n***EXCEPTION on Server***\n"  
          XS << "\n" + new Date().format('EEE MMM dd hh:mm:ss a yyyy') + "\n" 
          XS << e.toString() + "\n"
       }

    } 
 
    System.exit(12)
 }
