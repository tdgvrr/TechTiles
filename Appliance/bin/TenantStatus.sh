#!/bin/bash
# 
# Script to list the configured tenants on this system 
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
     kill -0 $PID > /dev/null 2>&1
     if [ "$?" -eq "0" ]; then 
        echo -e "    $DESC \tRunning ($PID)"
     else
        echo -e "    $DESC \tNOT Running"
        rm $PIDFILE > /dev/null 2>&1
     fi
 else
     echo -e "    $DESC \tNOT Running"
 fi
}

# Must have install directory (default: /appliance)

if [ -z "$FMA_DIR" ]; then
   export x=`readlink -f $0`
   export x=`dirname $x`/..
   export FMA_DIR=`readlink -f $x`
fi

. $FMA_DIR/conf/sysvars

GetStat "Login"
GetStat "Logoff"
GetStat "DB"
GetStat "Control"
GetStat "Action"

PIDFILE=$FMA_DIR/data

exit 0
 
