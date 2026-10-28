#!/bin/sh
# Tests URI selection logic with minimal Android stubs, not framework intent or URI permission behavior.
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
out_dir="$project_dir/build/share-uri-host"
mkdir -p "$out_dir"
javac -d "$out_dir" $(find app/src/test/host/share -name '*.java') \
    app/src/main/java/com/termux/app/terminal/clipboard/SharedImageUris.java
java -cp "$out_dir" com.termux.app.terminal.clipboard.SharedImageUrisHostTest
