#!/bin/bash

LOG=/var/log/TechTiles/VPNup2.log
SYS=/etc/TechTiles/Appliance

# Must have install directory (default: /appliance)

if [ -z "$FMA_DIR" ]; then
   export FMA_DIR=/appliance
fi

if [ ! -d "/var/log/TechTiles" ]; then
   mkdir -p /var/log/TechTiles
fi

NOW=`/bin/date`
echo "VPN2 UP: $NOW" >> $LOG
echo "     $@" >> $LOG

if [ -f "$FMA_DIR/bin/GetNet.sh" ]; then
   $FMA_DIR/bin/GetNet.sh
fi

if [ -d "/mnt/shared" ]; then
   mount -t nfs 10.8.0.1:/shared /mnt/shared >> $LOG 2>&1
fi

if [ -f "/usr/sbin/zabbix_agentd" ]; then
   service zabbix-agent start >> $LOG 2>&1
fi

# See if we have a multi-tenant appliance

if [ -f "$SYS" ]; then
   grep -i "Type=MULTI" $SYS
   if [ "$?" -eq "0" ]; then
      echo "     Multi-Tenant system starting" >> $LOG
      nohup $FMA_DIR/bin/StartTenant.sh&
      if [ -d "/mnt/shared/Tenants/INDEX" ]; then
	 FM_VPNIP=`/sbin/ip -4 -o addr show dev tun0 | head -n 1 | awk '{split($4,a,"/");print a[1]}'`
         echo "Add $HOSTNAME to tenant index on $FM_VPNIP" >> $LOG
	 echo "$HOSTNAME=$FM_VPNIP" > /mnt/shared/Tenants/INDEX/MultiTenant.$HOSTNAME
      fi	  
      exit 0
   fi
fi

if [ -f "$FMA_DIR/bin/JMSstart" ]; then
   nohup $FMA_DIR/bin/JMSstart&
fi

exit 0


