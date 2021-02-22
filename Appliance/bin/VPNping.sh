# 
# Script to check whether our network is active 
#

# Must have install directory (default: /appliance)

if [ -z "$FMA_DIR" ]; then
   export x=`readlink -f $0`
   export x=`dirname $x`/..
   export FMA_DIR=`readlink -f $x`
fi

export LOG=$FMA_DIR/logs
NOW=`/bin/date`
. $FMA_DIR/conf/sysvars

RC=`/sbin/ifconfig tun0 > /dev/null 2> /dev/null`
if [ "$?" -ne "0" ]; then
   echo "VPN does not appear to be running at $NOW"
   exit 1
fi

/bin/ping -c 1 -I tun0 `/bin/cat $FMA_DIR/conf/TThostIP` > /dev/null 2> /dev/null
if [ "$?" -ne "0" ]; then
   echo "VPN appears up, but JMS broker not reachable at $NOW"
   exit 2
fi

echo "Success: Network Ready at $NOW"

exit 0
