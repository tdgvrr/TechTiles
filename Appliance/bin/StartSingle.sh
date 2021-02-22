#!/bin/bash
# 
# Script to start a single configured tenant on a multi-tenant device 
#
# Syntax: "StartSingle.sh <tenantID>"

ROOT=/secure
LOG=/var/log/TechTiles/StartTenant.log
NOW=`/bin/date +%d-%b-%Y`

shopt -s nocasematch

echo ">>>$0 starting on $NOW" | tee -a $LOG

if [ -z "$1" ]; then 
   echo "ERROR: Missing tenant ID to start" | tee -a $LOG
   exit 4
fi

if [ ! -d "$ROOT/tenant.$1" ]; then
   echo "ERROR: Tenant $1 does not exist" | tee -a $LOG
   exit 4
fi

CONF=$ROOT/tenant.$1/conf

grep -i "ACTIVE=YES" $CONF/sysvars
if [ "$?" -ne "0" ]; then
   echo "ERROR: Tenant $1 is not active" | tee -a $LOG
   exit 4
fi

echo "Starting Tenant $1 from $ROOT/tenant.$1" | tee -a $LOG

export FMA_DIR=$ROOT/tenant.$1 
. $FMA_DIR/conf/sysvars
$FMA_DIR/bin/JMSstart | tee -a $LOG

exit 0
 
