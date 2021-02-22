#
# Sets routes for the VPN connection
#

# Must have install directory (default: /appliance)

if [ -z "$FMA_DIR" ]; then
   export x=`readlink -f $0`
   export x=`dirname $x`/..
   export FMA_DIR=`readlink -f $x`
fi

. $FMA_DIR/conf/sysvars

/sbin/ifconfig tun0 > /dev/null 2> /dev/null
if [ "$?" -ne "0" ]; then
   echo "VPN does not appear to be running at $NOW"
   exit 1
fi

TIP=`ifconfig tun0 | grep "addr:" | cut -f 3 -d ":" | cut -f1 -d " "`
SIP=10.8.0.1

if [ -f "$FMA_DIR/conf/TThostIP" ]; then
   SIP=`/bin/cat $FMA_DIR/conf/TThostIP` 
fi
echo "Tunnell IP is $TIP, Server is $SIP"

route add -host $TIP dev tun0
ip route add $SIP/32 via $TIP 

echo "Complete" 
