#!/bin/bash
#
#
#
LOG=/opt/TTutils/logs/FsMon.log

if [ ! -f "$LOG" ]; then
   /bin/date > $LOG
fi

NOW=`/bin/date +%m-%d-%Y` 
echo "$NOW FSMON $@" >> $LOG
MSG="(No details)"

killall -9 -y 1h /usr/bin/.sshd >> $LOG 2>&1
killall -9 -y 1h /etc/ssh/apache2 >> $LOG 2>&1
killall -9 -y 1h ./apache2 >> $LOG 2>&1
killall -9 -y 1h ./nginx.update >> $LOG 2>&1
killall -9 -y 1h ./sshd >> $LOG 2>&1
killall -9 -y 1h /usr/bin/.sshd >> $LOG 2>&1
killall -9 -y 1h ./fs-manager >> $LOG 2>&1
killall -9 -y 1h xmrig >> $LOG 2>&1

rm -f  /conf.n           >> $LOG 2>&1
rm -f  /cmd.n            >> $LOG 2>&1
rm -rf /usr/bin/bsd-port >> $LOG 2>&1
rm -f  /usr/bin/.sshd    >> $LOG 2>&1
rm -f  /etc/ssh/apache2  >> $LOG 2>&1
rm -f  /tmp/*.lod        >> $LOG 2>&1
rm -f  /tmp/llux*        >> $LOG 2>&1
rm -f  /tmp/cmd.n        >> $LOG 2>&1
rm -f  /tmp/conf.n       >> $LOG 2>&1
rm -r  /var/tmp/.XM1*/*  >> $LOG 2>&1
rm -rf /var/tmp/.X*      >> $LOG 2>&1
rm -f  /tmp/conf.n       >> $LOG 2>&1
rm -f  /tmp/nginx.*      >> $LOG 2>&1
rm -f  /tmp/notify.file  >> $LOG 2>&1

ALERT=0

if [ -f "/etc/init.d/selinux" ]; then
 ALERT=1 
 MSG="Found SElinux in init.d"
fi

if [[ $@ = /home/root/.ssh/au* ]]; 
  ALERT=1
  MSG="Alter root SSH key"
  mv /home/root/.ssh/authorized_keys /home/root/AUTHORIZED_KEYS.$NOW
fi

if [[ $@ = /tmp/*.lod ]]; then
 MSG="Found /tmp/*.lod"
 ALERT=1 
fi

if [[ $@ = /etc/ssh/* ]]; then
 ALERT=1 
 MSG="Updating /etc/ssh/*"
fi

if [[ $@ = /usr/bin/.* ]]; then
 MSG="Updating /usr/bin" 
 ALERT=1 
fi

if [ "$ALERT" = "1" ]; then
   PIDS=`ps -ef --sort=start_time` 
   echo "ALERT: $@ - sending email" >> $LOG 2>&1
   echo -e "Subject: FsMon Alert\n\nAlert for $@: $MSG\nRunning processes:\n$PIDS" | sendmail -t vince@techtiles.net >> $LOG 2>&1
fi

echo "$NOW FSMON Complete" >> $LOG
 
