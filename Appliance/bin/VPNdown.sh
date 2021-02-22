#!/bin/bash

logger "TechTiles: VPN is going DOWN"

NOW=`/bin/date`
LOG=/var/log/TechTiles/VPNup2.log

echo "VPN DOWN: $NOW" >> $LOG
ID=`id`
echo "           ID: $ID" >> $LOG 

echo "           Stopping services" >> $LOG
sudo /appliance/bin/JMSstoplocal >> $LOG 2>> $LOG 

echo "           Stopping Zabbix-agent" >> $LOG
if [ -f "/usr/sbin/zabbix_agentd" ]; then
   sudo service zabbix-agent stop&
fi

echo "           Erasing /appliance/data/netconfig" >> $LOG
sudo rm /appliance/data/netconfig 

exit 0

