SHELL=/bin/bash
HOME=/appliance
PATH=/appliance/bin:/usr/local/bin:/sbin:/bin:/usr/sbin:/usr/bin
FMA_DIR=/appliance
RANDOM_DELAY=55
# m h  dom mon dow   command
@reboot       /opt/TechTiles/OpenVault > /dev/null
@reboot       /appliance/bin/InstallConfig > /dev/null
@reboot       /appliance/bin/ClearLogs > /dev/null
@reboot       /appliance/bin/FixPass > /dev/null
*/30 * * * *  /appliance/bin/CRONjob   > /dev/null 
0    2 * * 5  /appliance/bin/SaveLog  >> /appliance/logs/SaveLog 
6    1 * * *  /appliance/bin/Maint -f INSTALL
