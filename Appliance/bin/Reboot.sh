#!/bin/bash

# Force a safe/fast reboot

sync 
mp=`df -t nfs4 --output=target | tail -n 1`
if [ ! -z "$mp" ]; then
   umount -f $mp
fi 
/sbin/reboot -f 

exit 0

