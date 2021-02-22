#!/bin/bash

HEADER=/opt/TTutils/conf/DYN.header
TEMPLATE=/opt/TTutils/conf/DYN.template
CFGFILE=/opt/TTutils/conf/DYN.cfg
NAGDIR=/usr/local/nagios/etc/objects
NAGCMD=/usr/local/nagios/bin/nagios

UpdateNag()
{
   grep -i "$1" $NAGDIR/*.cfg > /dev/null 2>&1
   if [ $? -ne 0 ]; then
      echo "Adding $1 to monitored hosts"
      export TARGET=$1
      eval "cat <<EOF
      $(<$TEMPLATE)
      EOF
      " 2> /dev/null | grep -vi "EOF" >> $CFGFILE
   else
      echo "Host $1 is already monitored"
   fi
}

# Ensure everything in EC2 is monitored with Nagios 

if [ ! -d "$NAGDIR" ]; then
   echo "WARNING: Nagios not installed - no $NAGDIR"
   exit 4
fi

IFS=$'\n'

cat $HEADER > $CFGFILE

LIST=`aws ec2 describe-instances --filter "Name=instance-state-name,Values=running" --output json | jshon -e Reservations -a -e Instances -a -e PublicIpAddress -u -p -e Tags -a -e Value -u`
INDEX=0
 
# The list is a little ugly because all the strings are newline separated

for i in $LIST; do
   INDEX=`expr $INDEX + 1`   
   if [ `expr $INDEX % 2` -eq 0 ]; then
      UpdateNag $i 
   else
      IP=$i
   fi
done

# Now we add it to Nagios if we actually found any good targets

if [ `stat --printf="%s" $CFGFILE` -gt `stat --printf="%s" $HEADER` ]; then
   echo "Installing updated Nagios configuration"
   cp $CFGFILE $NAGDIR
   killall nagios
   $NAGCMD -v /usr/local/nagios/etc/nagios.cfg
   $NAGCMD -d /usr/local/nagios/etc/nagios.cfg
   echo "Nagios reconfiguration complete"
else
   echo "No new hosts found"
fi

