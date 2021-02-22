# 
# Script to get our network IP addresses once VPN is active
#

# Must have install directory (default: /appliance)

if [ -z "$FMA_DIR" ]; then
   export x=`readlink -f $0`
   export x=`dirname $x`/..
   export FMA_DIR=`readlink -f $x`
fi

# VPN must be running - check for "openvpn" process and device tun0

oPID=`ps -ef | grep openvpn`

# Get *our* IP address on the tunnel

export FM_VPNIP=`/sbin/ip -4 -o addr show dev tun0 | head -n 1 | awk '{split($4,a,"/");print a[1]}'`

if [ -z "$FM_VPNIP" ]; then
   echo "ERROR: Can't determine local IP address"
   exit 4
else
   echo "Local VPN-assigned IP is $FM_VPNIP"
fi

# Now, get the peer's IP address

export FM_VPNPEER=`/sbin/ip -4 -o addr show dev tun0 | head -n 1 | awk '{split($6,a,"/");print a[1]}'`

if [ -z "$FM_VPNPEER" ]; then
   echo "ERROR: Can't determine peer IP address"
   exit 4
else
   echo "Remote VPN-assigned IP is $FM_VPNPEER"
fi

# Save the values

FILE=$FMA_DIR/data/netconfig
NOW=`date`
echo "# Values set by GetNet script at $NOW - do not edit" > $FILE
echo FM_VPNIP=$FM_VPNIP >> $FILE
echo FM_VPNPEER=$FM_VPNPEER >> $FILE
chmod 0666 $FILE

exit 0
 
