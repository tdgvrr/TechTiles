#!/bin/bash

# Must have install directory (default: /appliance)

if [ -z "$FMA_DIR" ]; then
       export FMA_DIR=/appliance
   fi

echo "$FMA_DIR/bin/VPNup2.sh $@" | at now + 1 minute

exit 0
