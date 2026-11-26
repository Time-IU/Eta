#!/system/bin/sh
# Run the Alpine-based Android Gradle build with Android's /system and /apex
# mounted only in a private mount namespace, so the SDK's ARM64 AAPT2 linker resolves.
set -eu
BB=/data/adb/ksu/bin/busybox
ROOTFS=/data/user/0/io.github.mangi.eta/files/terminal/alpine/rootfs
WORKSPACE=/data/local/tmp/eta
[ -x "$BB" ] || { echo "Root BusyBox missing" >&2; exit 127; }
[ -x "$ROOTFS/bin/busybox" ] || { echo "Alpine rootfs missing" >&2; exit 127; }
exec "$BB" unshare -m --propagation private "$BB" sh -c '
  set -eu
  BB=$1 ROOTFS=$2 WORKSPACE=$3
  "$BB" mount -o bind /system "$ROOTFS/system"
  "$BB" mount -o rbind /apex "$ROOTFS/apex"
  "$BB" mount -o bind /linkerconfig "$ROOTFS/linkerconfig"
  "$BB" mount -o rbind /dev "$ROOTFS/dev"
  "$BB" mount -t proc proc "$ROOTFS/proc"
  "$BB" mount -o bind "$WORKSPACE" "$ROOTFS/workspace"
  exec "$BB" chroot "$ROOTFS" /usr/bin/env -i \
    HOME=/root USER=root LOGNAME=root SHELL=/bin/sh TERM=dumb \
    LANG=C.UTF-8 LC_ALL=C.UTF-8 \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    /bin/sh -lc "cd /workspace/eta-ui-swipe-memory-patrol && sh test-alpine-compile.sh"
' sh "$BB" "$ROOTFS" "$WORKSPACE"
