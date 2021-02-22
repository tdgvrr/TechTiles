#!/bin/bash
# 
# Script to start the JMS services 
#

shopt -s nocasematch

GetStat () 
{
 DESC=$1
 LEN=${#DESC}
 if [ "$LEN" -lt "4" ]; then
     DESC=`echo $DESC "  "`
 fi

 PIDFILE=$FMA_DIR/data/$1.pid

 if [ -f "$PIDFILE" ]; then
     PID=`cat $PIDFILE | cut -d "=" -f 2`
     echo -e "    $DESC \tRunning ($PID)"
 else
     echo -e "    $DESC \tNOT Running"
 fi
}

# Must have install directory (default: /appliance)

export FMA_DIR=/appliance
CONF=/appliance/conf/sysvars

if [ "$UID" != "0" ]; then
   echo "ERROR: Incorrect privileges"
   exit 4
fi

if [ -f "/etc/TechTiles/Appliance" ]; then
   if [ ! -z `grep -i "Type=MULTI" /etc/TechTiles/Appliance` ]; then
      if [ -z "$2" ]; then
         echo "ERROR: Missing Tenant ID"
         exit 4
      fi
      CONF=/secure/tenant.$2/conf/sysvars
      export FMA_DIR=/secure/tenant.$2
      echo "Multi-tenant device - selecting tenant $2"
   fi
fi

. $CONF

echo "These services are currently running on tenant $tenant"
GetStat "Login"
GetStat "Logoff"
GetStat "DB"
GetStat "Control"
GetStat "Action"

if [ -f "/mnt/shared/latest/bin/JMSrestart" ]; then
   /mnt/shared/latest/bin/JMSrestart -t $2 $1 | grep -v "WARNING" 
else
   echo "WARNING: Service Restart not supported for Tenant $tenant" 
fi

exit 0
 
