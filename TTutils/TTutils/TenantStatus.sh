#!/bin/bash

LOG=/opt/TTutils/logs/TenantStatus.log
EMAIL1="vince@therefamily.net"
#EMAIL2="tdgvrr@gmail.com"
EMAIL2="OptiGate@financemgr.com"
SUBJ="OptiGate Tenant Status Report"
FILE1=/shared/TenantStatus.csv 
FILE2=/shared/TenantActivation.csv
Q1='SELECT * FROM tenantstatus2'
Q2='SELECT tenant_id as Tenant, date_format(min(created_date), "%d-%b-%Y") as Activated from fm_users where created_date > 0 and tenant_id > "00099" and tenant_id like "0%" group by tenant_id'

if [ ! -f "$LOG" ]; then
   /bin/date > $LOG
fi

NOW=`/bin/date +%m-%d-%Y` 
echo "$NOW TenantStatus Starts" >> $LOG

if [ ! -f "/etc/TechTiles/sysvars" ]; then
   echo "WARNING: No system variables" >> $LOG
   exit 8
fi

. /etc/TechTiles/sysvars

MSG=`mysql -u $TTDBUSER -p$TTDBPASS -h $TTDB --port $TTDBPORT -e "$Q1" FM > $FILE1 2>> $LOG`
echo -e "$Q1\n$MSG" >> $LOG
MSG=`mysql -u $TTDBUSER -p$TTDBPASS -h $TTDB --port $TTDBPORT -e "$Q2" FM > $FILE2 2>> $LOG`
echo -e "$Q2\n$MSG" >> $LOG

MSG1="<html><bold>Optigate Tenant Status Report as of $NOW</bold><br /><p><pre>" 
MSG2=`cat $FILE1` 
MSG3="</pre></p><br /><p><pre>"
MSG4=`cat $FILE2`
MSG5="</pre></p></html>"
sendemail -f "OptiGate <Info@TechTiles.net>" -t "$EMAIL1" -t "$EMAIL2" -u "$SUBJ" -m "$MSG1 $MSG2 $MSG3 $MSG4 $MSG5" -a $FILE1 -a $FILE2 -o message-content-type=html  >> $LOG 2>&1
echo "$NOW TenantStatus Complete" >> $LOG

exit 0  
