#!/usr/bin/env bash
# apktool wrapper used by repack.py
set -euo pipefail
export JAVA_HOME=/home/aglegend/tools/jdk-21.0.13+9
exec "$JAVA_HOME/bin/java" -jar /home/aglegend/tools/apktool.jar "$@"
