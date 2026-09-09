#!/bin/sh
# The staggered-rollout check: one server on the current build and one on the build at 76444608, both
# against the same database, both alive at the same time.
#
# It cannot be a JUnit test - the two builds cannot share a JVM - so it is two processes with a
# directory of marker files between them. Both are launched with plain `java` off each tree's own test
# runtime classpath, so no gradle daemon is held while they run.
#
#   sh Essential/src/test/resources/mixed-version/run-mixed-version.sh
#
# Environment:
#   DB_URL     r2dbc url for both instances   (default mariadb://127.0.0.1:3398/chip8_mixed)
#   DB_USER    (default root)   DB_PASS  (default empty)
#   OLD_TREE   where to put the 76444608 worktree (default <parent of this tree>/Essentials-mixed-old)
#   JAVA_HOME  required
#
# Exit status is the new instance's: 0 when the old instance reverted nothing, 1 when it did.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
NEW_TREE=$(cd "$HERE/../../../../.." && pwd)
OLD_TREE=${OLD_TREE:-$(dirname "$NEW_TREE")/Essentials-mixed-old}
OLD_REF=${OLD_REF:-76444608}
DB_URL=${DB_URL:-mariadb://127.0.0.1:3398/chip8_mixed}
DB_USER=${DB_USER:-root}
DB_PASS=${DB_PASS:-}
UUID=${UUID:-mixed-version-player}
RENDEZVOUS=${RENDEZVOUS:-$NEW_TREE/build/mixed-version}

: "${JAVA_HOME:?set JAVA_HOME to a JDK 21}"
JAVA="$JAVA_HOME/bin/java"

if [ ! -d "$OLD_TREE" ]; then
  echo "== creating a worktree at $OLD_REF in $OLD_TREE"
  git -C "$NEW_TREE" worktree add --detach "$OLD_TREE" "$OLD_REF"
fi
cp "$HERE/OldInstanceDriver.kt" "$OLD_TREE/Essential/src/test/kotlin/OldInstanceDriver.kt"

INIT=$(mktemp)
cat > "$INIT" <<'GRADLE'
allprojects {
    plugins.withId('java') {
        tasks.register('dumpTestRuntimeClasspath') {
            def out = new File(project.layout.buildDirectory.get().asFile, 'test-runtime-classpath.txt')
            def cp = project.sourceSets.test.runtimeClasspath
            outputs.upToDateWhen { false }
            doLast { out.text = cp.asPath }
        }
    }
}
GRADLE

build() {
  echo "== building $1"
  ( cd "$1" && ./gradlew :Essential:testClasses :Essential:dumpTestRuntimeClasspath \
      -I "$INIT" --console=plain --no-daemon --no-configuration-cache )
}
build "$OLD_TREE"
build "$NEW_TREE"

OLD_CP=$(cat "$OLD_TREE/Essential/build/test-runtime-classpath.txt")
NEW_CP=$(cat "$NEW_TREE/Essential/build/test-runtime-classpath.txt")

rm -rf "$RENDEZVOUS"
mkdir -p "$RENDEZVOUS"

echo "== starting the $OLD_REF instance"
( cd "$OLD_TREE/Essential" && "$JAVA" -cp "$OLD_CP" OldInstanceDriverKt \
    "$DB_URL" "$DB_USER" "$DB_PASS" "$UUID" "$RENDEZVOUS" ) > "$RENDEZVOUS/old.log" 2>&1 &
OLD_PID=$!

echo "== starting the current-build instance"
set +e
( cd "$NEW_TREE/Essential" && "$JAVA" -cp "$NEW_CP" essential.common.database.MixedVersionNewInstanceKt \
    "$DB_URL" "$DB_USER" "$DB_PASS" "$UUID" "$RENDEZVOUS" ) > "$RENDEZVOUS/new.log" 2>&1
RC=$?
set -e
wait "$OLD_PID" 2>/dev/null || true

echo "== old instance log"; cat "$RENDEZVOUS/old.log"
echo "== new instance log"; cat "$RENDEZVOUS/new.log"
exit "$RC"
