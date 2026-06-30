#!/bin/sh

set -e

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
JAVA_CMD=${JAVA_HOME:+$JAVA_HOME/bin/}java

exec "$JAVA_CMD" --enable-native-access=ALL-UNNAMED -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
