#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mvn install
mvn -f spike/pom.xml exec:java -Dexec.args=vulkan
