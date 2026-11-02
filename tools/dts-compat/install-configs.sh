#!/system/bin/sh
# Run only during an explicit install. MODPATH must be supplied by the caller.
# FUSION_ROOT may be overridden for host tests; keep the modules/dsp_fusion layout.
set -f
FUSION_ROOT=${FUSION_ROOT:-/data/adb/modules/dsp_fusion}
UPDATE_ROOT=${FUSION_ROOT%/*}/../modules_update/dsp_fusion
WORK=
COMMITTED=0

log() { printf '%s\n' "DTS configs: $*"; }
fail() { log "$*"; exit 1; }
hash_file() {
  hash_line=$(sha256sum "$1" 2>/dev/null) || return 1
  printf '%s\n' "${hash_line%% *}"
}
valid_row() {
  [ "${#BEFORE}" -eq 64 ] && [ "${#AFTER}" -eq 64 ] && [ -z "$EXTRA" ] || return 1
  case "$BEFORE$AFTER" in *[!0-9a-f]*) return 1 ;; esac
  case "$REL" in ''|/*|*[!A-Za-z0-9_./+-]*) return 1 ;; esac
  case "/$REL/" in *'/../'*|*'/./'*|*'//'*) return 1 ;; esac
  case "$REL" in system/etc/*|system/vendor/etc/*) ;; *) return 1 ;; esac
  case "${REL##*/}" in audio_effects*.xml|audio_effects*.conf) ;; *) return 1 ;; esac
}
safe_file() (
  probe=$1
  [ -d "$probe" ] && [ ! -L "$probe" ] || exit 1
  IFS=/
  for component in $2; do
    probe=$probe/$component
    [ ! -L "$probe" ] || exit 1
  done
  [ -f "$probe" ]
)
fusion_ready() {
  [ -d "$FUSION_ROOT" ] && [ ! -L "$FUSION_ROOT" ] &&
    [ ! -e "$FUSION_ROOT/disable" ] && [ ! -L "$FUSION_ROOT/disable" ] &&
    [ ! -e "$FUSION_ROOT/remove" ] && [ ! -L "$FUSION_ROOT/remove" ] &&
    [ ! -e "$UPDATE_ROOT" ] && [ ! -L "$UPDATE_ROOT" ]
}
replace_file() (
  source_file=$1 destination=$2 wanted=$3 expected_current=$4 relative=$5
  temporary=$(mktemp "$destination.dts-compat.XXXXXX") || exit 1
  trap 'rm -f "$temporary"' 0
  cp -f "$source_file" "$temporary" 2>/dev/null || exit 1
  [ "$(hash_file "$temporary")" = "$wanted" ] || exit 1
  chmod 0644 "$temporary" 2>/dev/null && chown 0:0 "$temporary" 2>/dev/null || exit 1
  if ! chcon --reference="$destination" "$temporary" 2>/dev/null; then
    case "$relative" in
      system/vendor/etc/*) context=u:object_r:vendor_configs_file:s0 ;;
      *) context=u:object_r:system_file:s0 ;;
    esac
    chcon "$context" "$temporary" 2>/dev/null || exit 1
  fi
  safe_file "$FUSION_ROOT" "$relative" || exit 1
  [ "$(hash_file "$destination")" = "$expected_current" ] || exit 1
  mv -f "$temporary" "$destination" 2>/dev/null || exit 1
)
rollback() {
  [ -s "$WORK/journal" ] || return 0
  log 'Rolling back this transaction.'
  awk '{ rows[NR]=$0 } END { for (i=NR;i>0;i--) print rows[i] }' "$WORK/journal" > "$WORK/reverse" || return 1
  while IFS=' ' read -r BEFORE AFTER REL EXTRA; do
    if ! safe_file "$FUSION_ROOT" "$REL"; then
      log "Rollback skipped unsafe/missing path: $REL"
      continue
    fi
    CURRENT=$(hash_file "$FUSION_ROOT/$REL") || CURRENT=unreadable
    if [ "$CURRENT" = "$AFTER" ]; then
      if safe_file "$MODPATH" "assets/original/$REL" &&
        replace_file "$MODPATH/assets/original/$REL" "$FUSION_ROOT/$REL" "$BEFORE" "$AFTER" "$REL"; then
        log "Rolled back: $REL"
      else
        log "ROLLBACK FAILED: $REL"
      fi
    elif [ "$CURRENT" != "$BEFORE" ]; then
      log "Rollback preserved externally changed file: $REL"
    fi
  done < "$WORK/reverse"
}
finish() {
  status=$1
  trap - 0 1 2 15
  if [ -n "$WORK" ]; then
    [ "$COMMITTED" -eq 1 ] || rollback
    rm -f "$WORK/list" "$WORK/journal" "$WORK/reverse"
    rmdir "$WORK" 2>/dev/null || true
  fi
  exit "$status"
}
trap 'finish $?' 0
trap 'exit 1' 1 2 15

[ -n "$MODPATH" ] && [ -d "$MODPATH" ] && [ ! -L "$MODPATH" ] || fail 'MODPATH must name the module directory.'
case "$MODPATH:$FUSION_ROOT" in /*:/*) ;; *) fail 'Module paths must be absolute.' ;; esac
fusion_ready || fail 'Fusion is missing, disabled, removed, or has a pending update.'
safe_file "$MODPATH" assets/configs.list || fail 'Missing or unsafe manifest.'
WORK=$(mktemp -d "$MODPATH/.config-install.XXXXXX") || fail 'Cannot create transaction directory.'
# Packaging may run on Windows. Normalize only CR at end of a manifest line;
# embedded control characters remain invalid and are rejected by valid_row.
CR=$(printf '\r')
sed "s/$CR\$//" "$MODPATH/assets/configs.list" > "$WORK/list" || fail 'Cannot snapshot manifest.'
: > "$WORK/journal" || fail 'Cannot create transaction journal.'

# Validate the entire batch before changing any Fusion configuration.
COUNT=0
SEEN='|'
while IFS=' ' read -r BEFORE AFTER REL EXTRA || [ -n "$BEFORE$AFTER$REL$EXTRA" ]; do
  valid_row || fail 'Invalid manifest row or unsupported path.'
  case "$SEEN" in *"|$REL|"*) fail "Duplicate path: $REL" ;; esac
  SEEN=$SEEN$REL'|'
  safe_file "$MODPATH" "assets/original/$REL" &&
    safe_file "$MODPATH" "assets/merged/$REL" &&
    safe_file "$FUSION_ROOT" "$REL" || fail "Missing or unsafe file: $REL"
  [ "$(hash_file "$MODPATH/assets/original/$REL")" = "$BEFORE" ] || fail "Original asset hash mismatch: $REL"
  [ "$(hash_file "$MODPATH/assets/merged/$REL")" = "$AFTER" ] || fail "Merged asset hash mismatch: $REL"
  CURRENT=$(hash_file "$FUSION_ROOT/$REL") || fail "Cannot read Fusion file: $REL"
  [ "$CURRENT" = "$BEFORE" ] || [ "$CURRENT" = "$AFTER" ] || fail "Fusion configuration has changed: $REL"
  COUNT=$((COUNT + 1))
done < "$WORK/list"
[ "$COUNT" -gt 0 ] || fail 'Empty manifest.'

while IFS=' ' read -r BEFORE AFTER REL EXTRA || [ -n "$BEFORE$AFTER$REL$EXTRA" ]; do
  fusion_ready || fail 'Fusion state changed during installation.'
  safe_file "$FUSION_ROOT" "$REL" || fail "Fusion path changed: $REL"
  CURRENT=$(hash_file "$FUSION_ROOT/$REL") || fail "Cannot read Fusion file: $REL"
  if [ "$CURRENT" = "$AFTER" ]; then
    log "Already merged: $REL"
    continue
  fi
  [ "$CURRENT" = "$BEFORE" ] || fail "Fusion configuration changed during installation: $REL"
  # Journal before rename: a failed rename is harmless because rollback checks AFTER.
  printf '%s %s %s\n' "$BEFORE" "$AFTER" "$REL" >> "$WORK/journal" || fail 'Cannot update transaction journal.'
  safe_file "$MODPATH" "assets/merged/$REL" &&
    replace_file "$MODPATH/assets/merged/$REL" "$FUSION_ROOT/$REL" "$AFTER" "$BEFORE" "$REL" || fail "Cannot install: $REL"
  log "Installed: $REL"
done < "$WORK/list"
fusion_ready || fail 'Fusion state changed before transaction completion.'
while IFS=' ' read -r BEFORE AFTER REL EXTRA || [ -n "$BEFORE$AFTER$REL$EXTRA" ]; do
  safe_file "$FUSION_ROOT" "$REL" &&
    [ "$(hash_file "$FUSION_ROOT/$REL")" = "$AFTER" ] || fail "Configuration changed before transaction completion: $REL"
done < "$WORK/list"
COMMITTED=1
log "Configuration installation complete ($COUNT files checked)."
