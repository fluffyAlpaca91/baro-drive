#!/usr/bin/env bash
# Run the Gradle wrapper for android/ from WSL with the Linux toolchain from ~/opt/baro-env.sh.
#
#   android/scripts/gradlew-wsl.sh assembleDebug test
#
# local.properties: Android Studio on Windows writes android/local.properties with a Windows
# sdk.dir (C\:\\Users\\...). AGP reads sdk.dir before ANDROID_HOME. A path that does not exist
# here is not fatal (AGP warns "Directory does not exist" and falls back to ANDROID_HOME), but
# to keep the build warning-free and independent of that fallback, this script moves such a
# file aside for the duration of the build and puts it back on exit.
#
# File-system watching needs no flag: Gradle detects that /mnt/c (9p/drvfs) cannot be watched,
# skips it, and keeps watching ~/.gradle on the Linux file system.
set -euo pipefail

android_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="${BARO_ENV_FILE:-$HOME/opt/baro-env.sh}"

log() { echo "gradlew-wsl: $*" >&2; }

if [[ ! -f "$env_file" ]]; then
  log "$env_file not found (set BARO_ENV_FILE to override)"
  exit 1
fi
# shellcheck source=/dev/null
source "$env_file"
cd "$android_dir"

props=local.properties
backup=local.properties.wsl-bak

# A previous run that was killed hard (SIGKILL) can leave the backup behind.
if [[ -f $backup && ! -e $props ]]; then
  mv "$backup" "$props"
  log "restored $props left behind by an interrupted run"
fi

restore_props() {
  [[ -f $backup ]] || return 0
  if [[ ! -e $props ]]; then
    mv "$backup" "$props"
    log "restored $props"
  elif cmp -s "$props" "$backup"; then
    rm -f "$backup"
    log "$props was recreated with the same content during the build; dropped the copy"
  else
    log "$props was rewritten during the build (Android Studio sync?); kept it, previous copy is $backup"
  fi
}

if [[ -f $props ]]; then
  # Last sdk.dir entry with java.util.Properties escapes removed (C\:\\Users -> C:\Users).
  sdk_dir="$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*[=:][[:space:]]*//p' "$props" | tail -n 1 | sed 's/\\\(.\)/\1/g')"
  if [[ -n $sdk_dir && ! -d $sdk_dir ]]; then
    mv "$props" "$backup"
    trap restore_props EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    trap 'exit 129' HUP
    log "moved $props aside for this build (sdk.dir=$sdk_dir does not exist in WSL); SDK from ANDROID_HOME=$ANDROID_HOME"
  fi
fi

# Not exec: the EXIT trap has to run after Gradle finishes.
./gradlew "$@"
