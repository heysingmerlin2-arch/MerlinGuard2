#!/usr/bin/env bash
set -euo pipefail
if ! command -v java >/dev/null 2>&1; then
  echo 'Java is not installed. Use a Codespace with Java 25.' >&2
  exit 1
fi
if ! command -v mvn >/dev/null 2>&1; then
  echo 'Maven is not installed. Use a Codespace with Maven.' >&2
  exit 1
fi
java -version
mvn -version
mvn --batch-mode clean package
printf '\nIf successful, inspect the JAR in target/\n'
