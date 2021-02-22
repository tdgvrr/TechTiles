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

if [ -f "/etc/TechTiles/Appliance" ]; then
   if [ ! -z `grep -i "Type=MULTI" /etc/TechTiles/Appliance` ]; then
      if [ -z "$1" ]; then
         echo "ERROR: Missing Tenant ID"
         exit 4
      fi
      CONF=/secure/tenant.$1/conf/sysvars
      export FMA_DIR=/secure/tenant.$1
      echo "Multi-tenant device - selecting tenant $1"
   fi
fi

. $CONF

GetStat "Login"
GetStat "Logoff"
GetStat "DB"
GetStat "Control"
GetStat "Action"

exit 0
 
