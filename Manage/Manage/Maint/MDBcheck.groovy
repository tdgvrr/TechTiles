import groovy.sql.Sql
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

// Load the desired JDBC driver 

jd = new File("/appliance/jms/lib/mysqlconnector-java-5.1.44-bin.jar")
if (jd.exists())
   this.class.classLoader.rootLoader.addURL( new URL("file:///appliance/jms/lib/mysql-connector-java-5.1.44-bin.jar"))
else
   this.class.classLoader.rootLoader.addURL( new URL("file:///appliance/jms/lib/mysql-connector-java-5.1.26-bin.jar"))

def env    = System.getenv()
def tid    = (env['TENANT'] ? env['TENANT'] : "")
def dbhost = (env['MAINT_DBHOST'] ? env['MAINT_DBHOST'] : "localhost")
def dbport = (env['MAINT_DBPORT'] ? env['MAINT_DBPORT'] : "21000" )
def dbuser = "maint"
def dbpass = "Smp4@dmv"
def dbdrvr = "com.mysql.jdbc.Driver"
def url    = "jdbc:mysql://" + dbhost + ":" + dbport + "/Management" 
Sql sql = new Sql()

    println "Connecting to $url"

    sql = Sql.newInstance(url, dbuser, dbpass, dbdrvr)
    if (!sql)
    {
       println "Maint: ERROR - Can't connect to database using " + url
       System.exit(8)
    }

    println "Maint: *DB Connected*"  

    // Show how many patches defined and applied

    def r = sql.firstRow("SELECT COUNT(*) AS Total FROM Patches")
    println "Maint: Total of ${r.Total} patches defined"

    if (tid)
    {
       println "Maint: Checking pending patches for tenant $tid..."
       r = sql.firstRow('SELECT COUNT(*) as Total FROM PatchStatus WHERE Tenant = "' + tid + '" and Result = 1')
       println "Maint: Total of ${r.Total} patches installed for tenant $tid"
       def q = 'SELECT * FROM Patches P WHERE NOT EXISTS ' +  
               '(SELECT * FROM PatchStatus as S WHERE P.ID = S.PatchID AND S.Tenant = "' + tid + '" AND S.Result > 0) ' +
               "ORDER BY P.Package, P.ID"    
       def plist = sql.rows(q)
       if (!plist)
          println "Maint: No pending patches - system is up to date" 
       else
       {
          println "Maint: ${plist.size()} patches pending"
          plist.each
          { p -> println "${p.ID}/${p.Created} in ${p.Package} - ${p.Desc}" }
       }
    }
    else
       println "Maint: No tenant - summary complete"
    sql.close()

    System.exit(0)
