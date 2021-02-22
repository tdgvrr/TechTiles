#!/bin/bash

# Sync /etc/hosts with VPN

VPN=/var/log/TechTiles/Appliance.lst
WORK=/opt/TTutils/work/hosts.P$$

# Called with <ip> <hostname>

AddOne()
{
 if [ -z "$1" ]; then
    echo "Missing host IP address"
    return
 fi
 
 if [ -z "$2" ]; then
    echo "Missing hostname"
    return
 fi

 if [[ "$2" != *"fma"* ]]; then
    echo "Invalid host $2 - skipped"
    return
 fi

 IP=$1
 H=$2
 FQH=$2.techtiles.net

 LINE=`echo -e "$IP\t$FQH $H"`
 grep -v $IP /etc/hosts | grep -v $H > $WORK 
 echo "$LINE" >> $WORK
 mv $WORK /etc/hosts 
 return
}

# Generate the whole list if nothing passed

if [ "$#" -eq "0" ];then
   while read -r vline; 
   do 
      VIP=`echo $vline | cut -f 1 -d ' '`
      VNODE=`echo $vline | cut -f 3 -d ':' | cut -f 1 -d ' '` 
      AddOne $VIP $VNODE  
   done < $VPN
   exit 0
fi

AddOne $1 $2 
exit 0
