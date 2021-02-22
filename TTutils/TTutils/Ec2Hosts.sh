#!/bin/bash

#
# Sync a local /etc/hosts file with hosts running in Amazon EC2. Any host attached 
# to the Amazon account is defined in the local /etc/hosts using the Amazon tag name
# as a host name, and the public IP address from Amazon.
#

ANSIBLE=/etc/ansible/hosts
DOMAIN=`domainname`
HOSTS=/etc/hosts
ifs=$IFS

UpdateHosts()
{
BASE=`grep -vi "$1" $HOSTS | grep -vi "$2" | grep -vi "Graylog Server "`
BASE=`echo -e "$BASE \n\n# $1 - AWS Dynamic Instance \n$2   $1.$DOMAIN   $1\n"`
echo -e "$BASE" > $HOSTS
}

# Synchronize EC2 hostnames with the local /etc/hosts table

IFS=$'\n'

LIST=`aws ec2 describe-instances --filter "Name=instance-state-name,Values=running" --output json | jshon -e Reservations -a -e Instances -a -e PublicIpAddress -u -p -e Tags -a -e Value -u`
INDEX=0

if [ -z "$LIST" ]; then
   echo "WARNING: No instance data returned by awscli"
   exit 4
fi
 
# The list is a little ugly because all the strings are newline separated

for i in $LIST; do
   INDEX=`expr $INDEX + 1`   
   if [ `expr $INDEX % 2` -eq 0 ]; then
      # Here, $i is the hostname (tag value from EC2) and $IP is the public IP
      if [ -f "$ANSIBLE" ]; then
         grep -i "$i" $ANSIBLE > /dev/null 2>&1
         if [ $? -ne 0 ]; then
            echo "WARNING: EC2 Host $i is not defined in the Ansible inventory (/etc/ansible/hosts)"
         fi 
      fi
      DNS=`nslookup $i.techtiles.net | grep -vi "127.0" | grep "Address:" | cut -f '2' -d ' '`
      if [ "$IP" = "$DNS" ]; then
         echo "$i is okay - in global DNS as $IP"
      else
         echo "$i is running on $IP, but DNS says $DNS"
         UpdateHosts $i $IP 
      fi
   else
      IP=$i
   fi
done

NEWHOSTS=`mktemp`
echo "# Dynamically constructed /etc/hosts file --" `date` "from" `basename $0` > $NEWHOSTS
echo " " >> $NEWHOSTS

for i in `grep "[^$]" /etc/hosts`; do 
   if [[ $i == \#* ]]; then 
      echo " " >> $NEWHOSTS
      echo "$i" >> $NEWHOSTS
      echo " " >> $NEWHOSTS
   else 
      i="$(echo -e "${i}" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
    # i="$(echo -e "${i}" | tr -d '[[:space:]]')"
      if [ ! -z "$i" ]; then
         echo "$i" >> $NEWHOSTS
      fi
   fi
done

mv /etc/hosts /etc/hosts.backup
mv $NEWHOSTS /etc/hosts
