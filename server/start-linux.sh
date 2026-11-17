#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
if [ ! -f paper.jar ]; then
  echo 'Missing server/paper.jar. Download a compatible Paper build.'
  exit 1
fi
exec java -Xms2G -Xmx4G -jar paper.jar --nogui
