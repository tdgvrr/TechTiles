#!/bin/bash

#
# Script to install S3FS - user-mode filesystem to access Amazon S3
#
# Complex, because it needs to be built on each system due to Linux
# kernel header dependencies. 
#
# 01Jun2016: New - VRR
#

S3CACHE=/tmp/s3cache
S3DIR=/opt/s3fs
S3CMD=/usr/bin/s3fs
MNTPT=/mnt/s3
BUCKET=techtiles-shared
PASSWD=/etc/s3fs/s3fs-cred
LOG=$S3DIR/BUILD.log
NOW=`/bin/date +%d-%b-%Y@%T`

mkdir -p $S3DIR 

# Check the mountpoint and mount our shared bucket

DoMount()
{
   echo "Mounting $BUCKET on $MOUNTPT..." >> $LOG

   if [ ! -d "$MNTPT" ]; then
      echo "Creating S3 mount point at $MNTPT" | tee -a $LOG
      mkdir -p $MNTPT
   fi

   if [ ! -d "$S3CACHE" ]; then
      echo "Creating S3 cache at $S3CACHE" | tee -a $LOG
      mkdir -p $S3CACHE
   fi

   s3fs $BUCKET $MNTPT -o passwd_file=$PASSWD -o use_cache=$S3CACHE -o stat_cache_expire=600 >> $LOG 
}


# See if it's installed already and exit if so

if [ -f "$S3CMD" ]; then
   $S3CMD --version > /dev/null 2>&1
   if [ "$?" -eq "0" ]; then
      echo "S3FS is already installed" 
      mounts=`df | grep -i s3fs`
      if [ -z "$mounts" ]; then
         DoMount 
      fi
      mkdir -p $MNTPT/hosts/`hostname -s`  
      exit 0
   fi
fi

# Here if S3FS is not installed - start by getting the build prereqs

echo "---Building S3FS on $HOSTNAME at $NOW---" | tee $LOG
cd $S3DIR

echo "Getting prerequisites installed..." | tee -a $LOG 
 
apt-get -y install build-essential unzip g++ make automake libtool mime-support \
                   libxml2 libcurl3-dev libxml2-dev libfuse-dev libcurl4-openssl-dev \
                   fuse libxml++2.6-dev libssl-dev >> $LOG 2>&1

if [ "$?" -ne 0 ]; then
   echo "WARNING: Prereq install error - check $LOG" | tee -a $LOG
   exit 4
fi

# Get the source

if [ ! -f "$S3DIR/master.zip" ]; then
   echo "Fetching source zip file..." | tee -a $LOG
   wget https://github.com/s3fs-fuse/s3fs-fuse/archive/master.zip >> $LOG 2>&1
fi

echo "Expanding source zip file..." | tee -a $LOG
unzip -o master.zip >> $LOG 2>&1
cd $S3DIR/s3fs-fuse-master
    
echo "Building..." | tee -a $LOGi
echo "AUTOGEN..." >> $LOG
./autogen.sh >> $LOG 2>&1
if [ "$?" -ne 0 ]; then
   echo "WARNING: AUTOGEN error - check $LOG" | tee -a $LOG
   exit 4
fi

echo "CONFIGURE..." >> $LOG
./configure --prefix=/usr >> $LOG 2>&1
if [ "$?" -ne 0 ]; then
   echo "CONFIGURE error - retrying with PKG_CONFIG_PATH" | tee -a $LOG
   PKG_CONFIG_PATH=`find / -name fuse.pc | head --lines 1` 
   ./configure --prefix=/usr >> $LOG 2>&1
   if [ "$?" -ne 0 ]; then
      echo "WARNING: CONFIGURE error - check $LOG" | tee -a $LOG
      exit 4
   fi
fi

echo "MAKE..." >> $LOG
make >> $LOG 2>&1
if [ "$?" -ne 0 ]; then
   echo "WARNING: MAKE error - check $LOG" | tee -a $LOG
   exit 4
fi

echo "MAKE INSTALL..." >> $LOG
make install >> $LOG 2>&1
if [ "$?" -ne 0 ]; then
   echo "WARNING: MAKE INSTALL error - check $LOG" | tee -a $LOG
   exit 4
fi

echo "Setting up..." | tee -a $LOG
mkdir -p `dirname $PASSWD`
echo 'AKIAJVZG2PREX4KZDX5Q:zT9RjnMze/ohJymeFL3b7xbIULxiWQcfy7lhUiKp' > $PASSWD
chmod 0600 $PASSWD
DoMount

echo "---S3FS Installation Complete---" | tee -a $LOG


