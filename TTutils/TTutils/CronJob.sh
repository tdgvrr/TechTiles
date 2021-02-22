#!/bin/bash

# Simple set of commands executed on every system under CRON control

find /var/log -mtime +30 -type f -exec rm {} \;
find /tmp/s3cache -mtime +10 -exec rm {} \;

