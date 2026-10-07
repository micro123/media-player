#!/bin/sh
set -eu
rpcbind -f &
sleep 1
/usr/local/sbin/unfsd -d -t -n 2049 -m 2049 &
smbd --foreground --no-process-group
