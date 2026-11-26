#!/bin/sh
set -eu
export LANG=C.UTF-8 LC_ALL=C.UTF-8
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
export GRADLE_USER_HOME=/workspace/eta343-upgrade/build-check/gradle-home
export PATH="$JAVA_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
unset LD_LIBRARY_PATH LD_PRELOAD ETA_RELEASE_STORE_FILE ETA_RELEASE_STORE_PASSWORD ETA_RELEASE_KEY_ALIAS ETA_RELEASE_KEY_PASSWORD
cd /workspace/eta-ui-swipe-memory-patrol
exec nice -n 15 ./gradlew --no-daemon --max-workers=1 --no-configuration-cache --console=plain \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' \
  :skill-policy:test
