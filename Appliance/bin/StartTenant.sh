#!/bin/bash
# 
# Script to start all configured tenants on a multi-tenant device 
#
ROOT=/secure
LOG=/var/log/TechTiles/StartTenant.log
NOW=`/bin/date +%d-%b-%Y`

shopt -s nocasematch

echo ">>>$0 starting on $NOW" | tee -a $LOG

# Install directory is /secure/tenant.##### 

for i in `ls -d $ROOT/tenant.*`
do
   CONF=$i/conf/sysvars
   TID=`echo $i | cut -f 2 -d '.'`
   grep -i "ACTIVE=YES" $CONF > /dev/null 2>&1
   if [ "$?" -eq "0" ]; then 
      export FMA_DIR=$i
      . $FMA_DIR/conf/sysvars
      echo "Starting Active tenant $TID from $i with FMA_DIR=$FMA_DIR" | tee -a $LOG
      $FMA_DIR/bin/JMSstart | tee -a $LOG
   else
      echo "Skipping inactive tenant $TID from $i" | tee -a $LOG
   fi
done

exit 0
 
