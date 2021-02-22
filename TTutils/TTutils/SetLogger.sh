#!/bin/bash

# SetSlaves: A script to create /etc/hosts entries for Amazon EC2 running slaves 
#
# This script uses the Amazon CLI to find our log server, then it prints
# the IP address for each one. The targets need to be tagged with NAME=Logger.
#
# The list is saved to /etc/hosts using hostnames of LOGGER (where "n" is the relative number) 
#
# 01May2016: New [VRR]
#

IFS=' '

LIST=`aws ec2 describe-instances --filters "Name=instance-state-name,Values=running" "Name=tag:Name,Values=Logger" | grep INSTANCES | cut -f 15`

BASE=`grep -vi logger /etc/hosts | grep -vi "Graylog Server"`
BASE=`echo -e "$BASE \n\n# Graylog Server - AWS Dynamic Instance\n"`
NUM=1

for i in $LIST; do
   echo Logger found at IP $i
   BASE=`echo -e "$BASE \n$i LOGGER"`
   NUM=`expr $NUM + 1`
done

echo -e $BASE > /etc/hosts

