#!/bin/sh
set -eu
cd "$(dirname "$0")"
exec java -Xms128m -Xmx512m -Dforge.relay.bind=127.0.0.1 -Dforge.relay.port=36744 \
    -Dforge.relay.health.bind=127.0.0.1 -Dforge.relay.health.port=36745 -jar forge-lobby-relay.jar
