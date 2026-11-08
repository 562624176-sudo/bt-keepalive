#!/bin/sh
APP_BASE_NAME=${0##*/}
APP_HOME=$( cd "${0%/*}/.." && pwd )
GRADLE_USER_HOME="${GRADLE_USER_HOME:-${HOME}/.gradle}"

exec "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
