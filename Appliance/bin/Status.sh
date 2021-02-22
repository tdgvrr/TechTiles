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

if [ -z "$FMA_DIR" ]; then
   export x=`readlink -f $0`
   export x=`dirname $x`/..
   export FMA_DIR=`readlink -f $x`
fi

. $FMA_DIR/conf/sysvars

if [ -z "$1" ]; then
   GetStat "Login"
   GetStat "Logoff"
   GetStat "DB"
   GetStat "Control"
   GetStat "Action"
else
   GetStat "$1"
fi

PIDFILE=$FMA_DIR/data

exit 0
 
