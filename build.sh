#!/bin/sh
set -eu
if ! command -v gradle >/dev/null 2>&1; then
  echo 'Gradle 9.6 and JDK 25 are needed. See README_RU.md.' >&2
  exit 1
fi
gradle --no-daemon build
printf '\nBuilt JAR: build/libs/\n'
