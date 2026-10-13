#!/system/bin/sh
# Restore only files still matching this module's merged hash. Never remove modules.
# Exit 0: restored/already original; 1: failure; 2: changed/unsafe paths preserved.
set -f
FUSION_ROOT=${FUSION_ROOT:-/data/adb/modules/dsp_fusion}
WORK=
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
finish() {
  status=$1
  trap - 0 1 2 15
  if [ -n "$WORK" ]; then
    rm -f "$WORK/list"
    rmdir "$WORK" 2>/dev/null || true
  fi
  exit "$status"
}
trap 'finish $?' 0
trap 'exit 1' 1 2 15

[ -n "$MODPATH" ] && [ -d "$MODPATH" ] && [ ! -L "$MODPATH" ] || fail 'MODPATH must name the module directory.'
case "$MODPATH:$FUSION_ROOT" in /*:/*) ;; *) fail 'Module paths must be absolute.' ;; esac
if [ ! -d "$FUSION_ROOT" ]; then
  log 'Fusion is absent; nothing to restore.'
  exit 0
fi
[ ! -L "$FUSION_ROOT" ] || fail 'Unsafe Fusion directory.'
safe_file "$MODPATH" assets/configs.list || fail 'Missing or unsafe manifest.'
WORK=$(mktemp -d "$MODPATH/.config-restore.XXXXXX") || fail 'Cannot create restore directory.'
CR=$(printf '\r')
sed "s/$CR\$//" "$MODPATH/assets/configs.list" > "$WORK/list" || fail 'Cannot snapshot manifest.'

COUNT=0
SEEN='|'
while IFS=' ' read -r BEFORE AFTER REL EXTRA || [ -n "$BEFORE$AFTER$REL$EXTRA" ]; do
  valid_row || fail 'Invalid manifest row or unsupported path.'
  case "$SEEN" in *"|$REL|"*) fail "Duplicate path: $REL" ;; esac
  SEEN=$SEEN$REL'|'
  safe_file "$MODPATH" "assets/original/$REL" || fail "Missing or unsafe original: $REL"
  [ "$(hash_file "$MODPATH/assets/original/$REL")" = "$BEFORE" ] || fail "Original asset hash mismatch: $REL"
  COUNT=$((COUNT + 1))
done < "$WORK/list"
[ "$COUNT" -gt 0 ] || fail 'Empty manifest.'

FAILED=0
SKIPPED=0
while IFS=' ' read -r BEFORE AFTER REL EXTRA || [ -n "$BEFORE$AFTER$REL$EXTRA" ]; do
  if ! safe_file "$FUSION_ROOT" "$REL"; then
    log "Skipped unsafe/missing path: $REL"
    SKIPPED=1
    continue
  fi
  CURRENT=$(hash_file "$FUSION_ROOT/$REL") || CURRENT=unreadable
  if [ "$CURRENT" = "$BEFORE" ]; then
    log "Already original: $REL"
  elif [ "$CURRENT" = "$AFTER" ]; then
    if safe_file "$MODPATH" "assets/original/$REL" &&
      replace_file "$MODPATH/assets/original/$REL" "$FUSION_ROOT/$REL" "$BEFORE" "$AFTER" "$REL"; then
      log "Restored: $REL"
    else
      log "RESTORE FAILED (file retained): $REL"
      FAILED=1
    fi
  else
    log "Skipped changed file; preserving future update: $REL"
    SKIPPED=1
  fi
done < "$WORK/list"
[ "$FAILED" -eq 0 ] || exit 1
[ "$SKIPPED" -eq 0 ] || exit 2
exit 0
