# 
# Script to establish the VPN connection 
#

# Must have install directory (default: /appliance)

if [ -z "$FMA_DIR" ]; then
   export x=`readlink -f $0`
   export x=`dirname $x`/..
   export FMA_DIR=`readlink -f $x`
fi

cd $FMA_DIR/bin

export VPN_HOST=`cat $FMA_DIR/conf/TThost`
export VPN_PORT=8889
export VPN_LOG=$FMA_DIR/logs/vpn.log
export VPN_CONF=$FMA_DIR/conf/openvpn.conf
export VPN_PID=$FMA_DIR/data/openvpn.pid
export VPN_UP=$FMA_DIR/bin/VPNup.sh
export VPN_DOWN=$FMA_DIR/bin/VPNdown.sh

VPN_OPTS="--remote $VPN_HOST --port $VPN_PORT --config $VPN_CONF --client --daemon --log-append $VPN_LOG --ping 30 --writepid $VPN_PID --script-security 3 --up $VPN_UP --down $VPN_DOWN --up-restart"

echo "Starting VPN using $VPN_OPTS" 
echo "Starting VPN using $VPN_OPTS" > $VPN_LOG
openvpn $VPN_OPTS >> $VPN_LOG 2>> $VPN_LOG

exit 0
 
