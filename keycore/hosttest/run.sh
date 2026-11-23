#!/bin/sh
# Builds keycore for this PC and runs the JNI bridge test with a real JVM.
set -e
cd "$(dirname "$0")/.."
cargo build --release
OUT=$(mktemp -d)
javac -d "$OUT" hosttest/com/ssonkoict/keyboard/NativeCore.java
java -Djava.library.path=target/release -cp "$OUT" com.ssonkoict.keyboard.NativeCore
