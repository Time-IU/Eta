#!/bin/sh
set -eu
export LANG=C.UTF-8 LC_ALL=C.UTF-8
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
export ANDROID_HOME=/workspace/eta343-upgrade/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME=/workspace/eta343-upgrade/build-check/gradle-home
export PATH="$JAVA_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
unset LD_LIBRARY_PATH LD_PRELOAD ETA_RELEASE_STORE_FILE ETA_RELEASE_STORE_PASSWORD ETA_RELEASE_KEY_ALIAS ETA_RELEASE_KEY_PASSWORD
cd /workspace/eta-ui-swipe-memory-patrol
exec nice -n 15 ./gradlew --offline --no-daemon --max-workers=1 --no-configuration-cache --console=plain \
  -Pandroid.aapt2FromMavenOverride=/workspace/eta343-upgrade/build-check/toolbin/aapt2 \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=768m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' \
  :app:testDebugUnitTest \
  --tests 'io.github.mangi.eta.ui.components.PageSwipeBackPolicyTest' \
  --tests 'io.github.mangi.eta.ui.components.TopBarFeedbackContractTest' \
  --tests 'io.github.mangi.eta.ui.app.AgentMemoryDocumentActionsTest' \
  --tests 'io.github.mangi.eta.ui.screens.memory.MemoryNoticeVisibilityTest' \
  --tests 'io.github.mangi.eta.data.model.AppearanceSettingsTest' \
  :app:assembleDebug
