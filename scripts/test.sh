#!/bin/sh
# Everything that runs without a device. Run before every commit.
set -eu
cd "$(dirname "$0")/.."
scripts/gradle.sh :core:kit:test :runtime:glfw-shim:test testDebugUnitTest verifyRoborazziDebug lintDebug assembleDebug "$@"
