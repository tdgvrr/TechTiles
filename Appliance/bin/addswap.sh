#!/bin/bash

# Ensure we have a usable swap area and reallocate if not
FILE=/swapfile
GOAL=12884901888
BLKS=12288
SIZE=0

# If file doesn't exist, create it here

if [ ! -f "$FILE" ]; then    
   sudo dd if=/dev/zero of=$FILE bs=1M count=$BLKS
   sudo chmod 0600 $FILE
   sudo mkswap $FILE
   sudo swapon $FILE
   exit 0
fi

# Swap exists - see how big it is and whether it's online

SIZE=`stat -c %s $FILE`
sudo swapon | grep "$FILE" > /dev/null
if [ "$?" -eq "0" ]; then     
   # The swapfile is already in use - check size
   if [ "$SIZE" -lt "$GOAL" ]; then
      # Too small - reallocate
      sudo swapoff $FILE > /dev/null 2>&1
      sudo rm $FILE
      sudo dd if=/dev/zero of=$FILE bs=1M count=$BLKS
      sudo chmod 0600 $FILE
      sudo mkswap $FILE
      sudo swapon $FILE
      exit 0
   fi
   # Online and right size - nothing to do
   exit 0
fi

# File exists, but isn't online
if [ "$SIZE" -lt "$GOAL" ]; then
   sudo rm $FILE
   sudo dd if=/dev/zero of=$FILE bs=1M count=$BLKS
   sudo chmod 0600 $FILE
   sudo mkswap $FILE
fi

sudo swapon $FILE
   
exit 0
