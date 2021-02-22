#!/bin/bash

LOG=/var/log/TechTiles/CliCon.log
NOW=`/bin/date`
DIR=/opt/TTutils             # Install Directory
PEM=$DIR/conf/Appliance.pem  # Private key to login
CACHE=$DIR/data/Status2.cache
MAXAGE=900
SSHOPT="-o UserKnownHostsFile=/dev/null"

if [ -z "$1" ]; then 
   echo "ERROR: No IP"
   exit 8
fi

if [ "$EUID" != "0" ]; then
   echo "Not running with root privileges"
   exit 8
fi

echo "Connecting to IP $1"

# The Status script can be slow, so we have a cached version

MSG=`echo "" | ssh -p 11022 -i $PEM master@$1 2>/dev/null`
TENANT=`echo "$MSG" | grep "Tenant ID:" | cut -f 2 -d ":" | cut -f 3 -d " "`
TENANT=`echo $TENANT`

echo "Tenant is: $TENANT" 
exit 0
