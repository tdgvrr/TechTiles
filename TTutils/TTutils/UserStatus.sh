#!/bin/bash

LOG=/opt/TTutils/logs/UserStatus.log
#EMAIL1="vince@therefamily.net"
EMAIL1="rmeditz@financemgr.com"
EMAIL2="tdgvrr@gmail.com"
SUBJ="OptiGate User Status Report"
S3FILE="s3://techtiles-fm-shared/UserStatus.csv"
AWS=/usr/local/bin/aws
Q1='SELECT * FROM userstatus'

if [ ! -f "$LOG" ]; then
   /bin/date > $LOG
fi

NOW=`/bin/date +%d-%b-%Y` 
echo "----------$NOW UserStatus Starts----------" >> $LOG

if [ ! -f "/etc/TechTiles/sysvars" ]; then
   echo "WARNING: No system variables" >> $LOG
   exit 8
fi

. /etc/TechTiles/sysvars

FILE1=/tmp/UserStatus-$NOW.csv
S3FILE="s3://techtiles-fm-shared/`basename $FILE1`"
MSG=`mysql -u $TTDBUSER -p$TTDBPASS -h $TTDB --port $TTDBPORT -e "$Q1" FM > $FILE1 2>> $LOG`
echo -e "$Q1\n$MSG" >> $LOG
LINES=`wc -l /shared/UserStatus.csv | cut -f 1 -d ' '`
echo "Result set is $LINES lines" >> $LOG

$AWS s3 cp $FILE1 $S3FILE >> $LOG 2>&1
URL=`$AWS s3 presign $S3FILE --expires-in 604800`
echo "AWS S3 URL is $URL" >> $LOG

MSG1="<html><bold>Optigate User Status as of $NOW</bold><br /><p>" 
MSG2="Click the link below to download a file containing information about $LINES user accounts in CSV format.</p><p>"
MSG3="This link automatically expires after 7 days: <a href=$URL>`basename $FILE1`</a>"  
MSG5="</p></html>"
sendemail -f "OptiGate <Info@TechTiles.net>" -t "$EMAIL1" -u "$SUBJ" -m "$MSG1 $MSG2 $MSG3 $MSG5" -o message-content-type=html  >> $LOG 2>&1
mv $FILE1 /shared/UserStatus.csv

echo "$NOW UserStatus Complete" >> $LOG

exit 0  
