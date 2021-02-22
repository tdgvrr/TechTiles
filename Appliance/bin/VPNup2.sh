#!/bin/bash

LOG=/var/log/TechTiles/VPNup2.log
SYS=/etc/TechTiles/Appliance
NOW=`/bin/date`
ID=`id`

if [ ! -d "/var/log/TechTiles" ]; then
   mkdir -p /var/log/TechTiles
fi

if [ ! -f "$LOG" ]; then
   echo "*** VPNup2.log initialized $NOW ***" > $LOG
fi
echo "VPN2 UP: $NOW" >> $LOG
echo "     $@" >> $LOG
echo "     ID: $ID" >> $LOG

# Must have install directory (default: /appliance)

if [ -z "$FMA_DIR" ]; then
   export FMA_DIR=/appliance
fi

# Get the new network info 

if [ -f "$FMA_DIR/bin/GetNet.sh" ]; then
   echo "     Run $FMA_DIR/bin/GetNet.sh" >> $LOG
   $FMA_DIR/bin/GetNet.sh
fi

# Get our NFS share mounted 

if [ -d "/mnt/shared" ]; then
   grep "/mnt/shared" /etc/fstab > /dev/null 2>&1
   if [ "$?" -eq "0" ]; then
      echo "     Mounting NFS share using /etc/fstab defaults" >> $LOG
      # mount /mnt/shared >> $LOG 2>&1
      mount -t nfs -o soft,timeo=100,async 10.8.0.1:/shared /mnt/shared >> $LOG 2>&1
   else
      echo "     Mounting NFS share" >> $LOG
      mount -t nfs -o soft,timeo=100,async 10.8.0.1:/shared /mnt/shared >> $LOG 2>&1
   fi
fi

# Start or Restart the Zabbix Agent 

if [ -f "/usr/sbin/zabbix_agentd" ]; then
   echo "     Starting Zabbix-Agent" >> $LOG
   service zabbix-agent restart >> $LOG 2>&1
fi

# See if we have a multi-tenant appliance - the script below starts up all configured tenants

if [ -f "$SYS" ]; then
   grep -i "Type=MULTI" $SYS
   if [ "$?" -eq "0" ]; then
      echo "     Multi-Tenant system starting" >> $LOG
      nohup $FMA_DIR/bin/StartTenant.sh&
      if [ -d "/mnt/shared/Tenants/INDEX" ]; then
         # This really isn't needed anymore since the server-side VPN implementation tracks our IP too
	 FM_VPNIP=`/sbin/ip -4 -o addr show dev tun0 | head -n 1 | awk '{split($4,a,"/");print a[1]}'`
         echo "Add $HOSTNAME to tenant index on $FM_VPNIP" >> $LOG
	 echo "$HOSTNAME=$FM_VPNIP" > /mnt/shared/Tenants/INDEX/MultiTenant.$HOSTNAME
      fi	  
      nohup $FMA_DIR/bin/StartTenant.sh&
      exit 0
   fi
fi

# This is the single-tenant path 

ip=`ifconfig tun0 | grep "inet " | cut -f 2 -d ":" | cut -f 1 -d " "`
if [ -z "$TENANT" ]; then
   TENANT=`grep -i TENANT= $FMA_DIR/conf/sysvars | cut -f 2 -d "="`
fi 
echo "$ip" > /mnt/shared/Tenants/INDEX/Tenant.$TENANT

# When the VPN starts, we grab copies of the key config files 

if [ -f "/mnt/shared/cfg_capture" ]; then
   echo "     Captured configuration settings" >> $LOG
   /mnt/shared/cfg_capture
fi

# Now, finally we can start the JMS Services 

if [ -f "$FMA_DIR/bin/JMSstart" ]; then
   echo "     Starting TechTiles services" >> $LOG
   nohup $FMA_DIR/bin/JMSstart&
fi

exit 0
