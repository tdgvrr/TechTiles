#!/bin/bash

#
# Simple automation script to call the "aws configure" command.  
# 
# New: 01Jun2016 - VRR
#

# Parameters come in as $1 = aws_access_key_id $2 = $aws_secret_access_key $3 = $region, $4 = $output  

echo -e "$1\n$2\n$3\n$4\n" | aws configure

exit $?  
