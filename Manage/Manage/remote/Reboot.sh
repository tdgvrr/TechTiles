#!/bin/bash

# Force a safe/fast reboot

LOG="/var/log/TechTiles/Reboot.log"

echo "Reboot process starting at " `/bin/date` >> $LOG
id >> $LOG
uptime >> $LOG

sync >> $LOG 
umount -lft nfs >> $LOG
/sbin/reboot -f >> $LOG

exit 0

