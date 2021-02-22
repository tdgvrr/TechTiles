#!/bin/bash
# 
# Script to start all configured tenants on a multi-tenant device 
#
ROOT=/secure
NOW=`/bin/date +%d-%b-%Y`

shopt -s nocasematch

for i in `ls -d $ROOT/tenant.*`
do
    echo "In $i"
    cp /appliance/bin/GetVPNip $i/bin   
    cp /appliance/jms/Control.groovy $i/jms   
done

exit 0
 
