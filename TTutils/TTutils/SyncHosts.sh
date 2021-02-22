#!/bin/bash

#
# This is a script to setup some of the management parts across a network of hosts 
# using Ansible and a variety of other tools to get monitoring, log forwarding, etc. 
# configured. 
# 
# Before running this, check the host inventory in /etc/ansible/hosts - edit by hand if needed. 
#
# Assumes that the default DA.PEM keys can connect to every target - see Ansible. 
#
# Must also be repeatable (that is, can be run many times without breaking anything)
#
# New: 01Jun2016 - VRR
#

TARGET=servers			# Default if no particular host specified. 
SCRIPT=/opt/TTutils 		# Default path to our utilities
NOW=`/bin/date +%d-%b-%Y@%T`
LOG=/opt/TTutils/logs/SyncHost-$NOW.log 

if [ ! -z "$1" ]; then
   TARGET=$1
fi

echo "---SyncHost: $NOW Running on: $HOSTNAME Targeting: $TARGET---" | tee $LOG

# First, we make sure we have the correct network settings for each system (some are DHCP)

echo "   Fixup /etc/hosts for dynamic systems..." | tee -a $LOG
$SCRIPT/Ec2Hosts.sh >> $LOG 2>&1 

# Next, we need to create some directories

echo "   Create directories on target systems..." | tee -a $LOG
ansible -m command -a "sudo mkdir -p /opt/TTutils/conf" $TARGET >> $LOG 2>&1
ansible -m command -a "sudo mkdir -p /opt/TTutils/logs" $TARGET >> $LOG 2>&1

# Copy all the scripts and data (except for log files)

echo "   Synchronizing scripts and utilities..." | tee -a $LOG
for i in `find $SCRIPT -type f | grep -v /logs/`; 
do
  f=`realpath $i`  
  ansible -m copy -a "src=$f dest=$f mode=755" --become $TARGET >> $LOG 2>&1
done

# Setup the AWSCLI stuff

echo "   Setting up AWS CLI..." | tee -a $LOG
ansible -m command -a "sudo apt-get update" $TARGET >> $LOG 2>&1
ansible -m command -a "sudo apt-get -y install awscli" $TARGET >> $LOG 2>&1
ansible -m command -a "sudo apt-get -y install jshon" $TARGET >> $LOG 2>&1
if [ -d "$HOME/.aws" ]; then
   ifs=$IFS
   IFS=$'\n'
   for i in `cat $HOME/.aws/* | grep -v '\['`; 
   do
      id=`echo $i | cut -f 1 -d ' '`
      val=`echo $i | cut -f 3 -d ' '`
      export $id=$val
   done
   IFS=$ifs 
   ansible -m command -a "/opt/TTutils/SetupAWS.sh '$aws_access_key_id' '$aws_secret_access_key' '$region' '$output'" --become $TARGET >> $LOG 2>&1
else
   echo "WARNING: AWS Credentials not found" | tee -a $LOG
fi

ansible -m command -a "sudo /opt/TTutils/Ec2Hosts.sh" --become $TARGET >> $LOG 2>&1

echo "   Setting up S3FS..." | tee -a $LOG
ansible -m command -a "sudo /opt/TTutils/SetupS3FS.sh" --become $TARGET >> $LOG 2>&1

echo "   Setting up rsyslog..." | tee -a $LOG
ansible -m command -a "sudo /opt/TTutils/ForwardSyslog.sh" --become $TARGET >> $LOG 2>&1

echo "   Setting up CRON..." | tee -a $LOG
ansible -m cron -a "job='/opt/TTutils/CronJob.sh >> /opt/TTutils/logs/CRON.log 2>&1' special_time='daily' state='absent'" --become $TARGET >> $LOG 2>&1

echo "   Setting up Nagios..." | tee -a $LOG
$SCRIPT/DynNagios.sh >> $LOG 2>&1

END=`/bin/date +%d-%b-%Y@%T`
echo "---Sync Complete at $END---" | tee -a $LOG 

