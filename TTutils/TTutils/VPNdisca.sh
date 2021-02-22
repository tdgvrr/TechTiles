#!/bin/bash

LOG=/var/log/TechTiles/VPNcli.log
INV=/var/log/TechTiles/Appliance.lst
NOW=`/bin/date`

TENANT=`grep "$ifconfig_pool_remote_ip" $INV | cut -f 2 | cut -f 2 -d ':'`
if [ -z "$TENANT" ]; then
   TENANT="*UNKNOWN*"
fi
echo "$NOW - Disconnect Tenant $TENANT on $trusted_ip as $ifconfig_pool_remote_ip after $bytes_sent sent, $bytes_received received, $time_duration sec." >> $LOG 

if [ -f "$INV" ]; then
   grep -v "$ifconfig_pool_remote_ip" $INV | grep -v "Tenant:$TENANT" > $INV.dwork
   sort -u --key 1,1 $INV.dwork > $INV
   rm $INV.dwork
fi

exit 0
