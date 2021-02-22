#!/bin/bash

LOG=/var/log/TechTiles/VPNcli.log
STAT=/opt/TTutils/StatOne 
NOW=`/bin/date`

echo "$NOW - Connect $trusted_ip as $ifconfig_pool_remote_ip " >> $LOG 

if [ -f "$STAT" ]; then
   echo "sudo $STAT $ifconfig_pool_remote_ip >> $LOG &2>1" | at now + 1 minute
fi

exit 0
