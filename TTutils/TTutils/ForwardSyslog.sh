#!/bin/bash

# ForwardSyslog: A script to update rsyslogd to forward log entries to our Graylog log server 
#
# 01Jun2016: New [VRR]
#

TARGETS=/etc/rsyslog.d/*.conf
TARGET=/etc/rsyslog.d/10-TTlogger.conf
LINE="*.* 				@@LOGGER:514;RSYSLOG_SyslogProtocol23Format"

grep -i "@@LOGGER:514" $TARGETS > /dev/null 2>&1
if [ $? -ne 0 ]; then
   echo "Updating $TARGET"
   echo "# Forward all messages to TechTiles Log Server" >> $TARGET
   echo " " >> $TARGET
   echo "$LINE" >> $TARGET
   service rsyslog restart
   if [ -f "/var/run/rsyslogd.pid" ]; then
      kill -HUP $(cat /var/run/rsyslogd.pid)
   fi
   logger "$0 - Updated $TARGET"
else
   echo "$0 - rsyslog already configured"
   exit 4
fi

