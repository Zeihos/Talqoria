#!/bin/sh
set -eu
cd "$(dirname "$0")"
data_dir="${1:-server/plugins/TalqoriaCore}"
mkdir -p plugin/target/inspector-cli
javac -encoding UTF-8 -d plugin/target/inspector-cli \
  plugin/src/main/java/ru/talqoria/core/AuditLedger.java \
  plugin/src/main/java/ru/talqoria/core/MarketJournal.java \
  plugin/src/main/java/ru/talqoria/core/OfflineInspector.java \
  plugin/src/main/java/ru/talqoria/core/PlacedBlockLedger.java
java -cp plugin/target/inspector-cli ru.talqoria.core.OfflineInspector "$data_dir"
