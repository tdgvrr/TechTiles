# 
# Script to populate several key items in the CONF directory
#

# Get our install directory (default: /appliance)

if [ -z "$FMA_DIR" ]; then
   export x=`readlink -f $0`
   export x=`dirname $x`/..
   export FMA_DIR=`readlink -f $x`
fi

echo "Install Directory is $FMA_DIR" 
export LOG=$FMA_DIR/logs/Init.log
echo "$0 START at `/bin/date`" > $LOG
echo "Init: Install directory is $FMA_DIR" >> $LOG

# Setup the system ID (serial) and type
 
dmidecode -s baseboard-serial-number > $FMA_DIR/conf/SystemID
dmidecode -s baseboard-product-name > $FMA_DIR/conf/SystemType
echo "Init: System ID is `cat $FMA_DIR/conf/SystemID`" >> $LOG
echo "Init: System Type is `cat $FMA_DIR/conf/SystemType`" >> $LOG 
   
# Be sure we have connectivity 

if [ ! -f $FMA_DIR/conf/TThost ]; then 
  echo "ERROR: Missing TechTiles host"
  echo "Init: ERROR No TThost file" >> $LOG
  exit 4
fi

export FMA_TTHOST=`cat $FMA_DIR/conf/TThost`
echo "TechTiles host is $FMA_TTHOST"
curl $FMA_TTHOST > $FMA_DIR/logs/ping-tthost 2> $FMA_DIR/logs/ping-tthost

if [ "$?" -gt 0 ]; then
   echo "ERROR: Can't connect to $FMA_TTHOST"
   echo "Init: ERROR PING $FMA_TTHOST" >> $LOG
   exit 4
fi

echo "Configuration complete - successful operation" 
echo "Init: SUCCESS" >> $LOG
exit 0
 
