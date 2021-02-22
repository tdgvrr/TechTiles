import groovy.sql.Sql
import java.sql.Connection
import java.sql.Statement
import java.sql.DatabaseMetaData
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

def driver = new File("/appliance/jms/lib/mssql-jdbc-8.4.1.jre8.jar")
if (driver.exists())
   this.class.classLoader.rootLoader.addURL( new URL("file:///appliance/jms/lib/mssql-jdbc-8.4.1.jre8.jar"))
else
   this.class.classLoader.rootLoader.addURL( new URL("file:///appliance/jms/lib/sqljdbc42.jar"))

def env = System.getenv()
def dbhost = env["JMS_DBHOST"]
def dbuser = env["JMS_DBUSER"]
def dbpass = env["JMS_DBPASS"]
def dbport = env["JMS_DBPORT"]
def dbschema = env["JMS_DBSCHEMA"]
def dbdrvr = "com.microsoft.sqlserver.jdbc.SQLServerDriver"
def sep = ";selectMethod=cursor;responseBuffering=adaptive;Database="

if (args.size() != 5)
{
   println "ERROR: Args are <host> <port> <schema> <user> <pass>"
   System.exit(12)
}
dbhost  = args[0].trim()
dbport  = args[1].trim()
dbschema= args[2].trim()
dbuser  = args[3].trim()
dbpass  = args[4].trim()

def url = "jdbc:sqlserver://" + dbhost + ":" + dbport + sep + dbschema
def query = "select count(*) FROM sys.parameters"

try
{
    Sql sql = new Sql()
    println "Connecting to $dbhost:$dbport database $dbschema user $dbuser password $dbpass"
    sql = Sql.newInstance(url, dbuser, dbpass, dbdrvr)
    if (!sql)
    {
       println "ERROR - Can't connect to database"
       System.exit(4)
    }
    println "Connected to database" 
    DatabaseMetaData metaData = sql.getConnection().getMetaData()
    println "JDBC driver version: " + metaData.getDriverVersion()
    println "Database is: " + metaData.getDatabaseProductName() + " " + metaData.getDatabaseProductVersion() 

    def result = sql.rows(query)
    println "Processed test query"
    sql.close()
    System.exit(0) 
}
catch (Exception e)
{
    println "FAIL: Can't connect to database (scroll left/right to see entire message below)"
    def emsg = e.toString().replace("xception: ", "xception:\n  ");
    println "FAIL: $emsg"
    System.exit(8) 
}

