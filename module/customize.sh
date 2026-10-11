#!/system/bin/sh
SKIPUNZIP=0
ui_print "DSP Fusion 0.1.0-alpha1"
ui_print "One module / one effect / one application"
[ "$API" -ge 26 ] || abort "Android 8.0 or newer is required."
case "$ARCH" in
  arm64) ;;
  arm) rm -rf "$MODPATH/system/vendor/lib64" ;;
  *) abort "This build supports ARM and ARM64 only." ;;
esac

for OLD in ainur_jamesdsp ViPER4Android-RE-Fork ViPER4Android-RE-AIDL aml; do
  if [ -d "/data/adb/modules/$OLD" ] && [ ! -f "/data/adb/modules/$OLD/disable" ] && [ ! -f "/data/adb/modules/$OLD/remove" ]; then
    abort "Disable $OLD in Magisk first, then install DSP Fusion."
  fi
done

# Build private systemless copies. Never edit the live vendor files.
PATCHED=0
UUID=30306067-9400-4d53-9000-000000000001
for ROOT in /vendor/etc /system/etc /odm/etc /product/etc /system_ext/etc; do
  [ -d "$ROOT" ] || continue
  for SRC in $(find "$ROOT" -type f \( -name '*audio_effects*.xml' -o -name '*audio_effects*.conf' \) 2>/dev/null); do
    case "$SRC" in *haptic*) continue ;; esac
    case "$SRC" in
      /system/*) DEST="$MODPATH$SRC" ;;
      *) DEST="$MODPATH/system$SRC" ;;
    esac
    mkdir -p "${DEST%/*}"
    cp -f "$SRC" "$DEST" || abort "Cannot copy $SRC"
    case "$SRC" in
      *.xml)
        grep -q '<libraries>' "$DEST" || continue
        grep -q '<effects>' "$DEST" || continue
        sed -i '/<library[^>]*name="dsp_fusion"/d; /<effect[^>]*name="dsp_fusion"/d' "$DEST"
        sed -i '/<libraries>/a\        <library name="dsp_fusion" path="libdspfusion.so"/>' "$DEST"
        sed -i "/<effects>/a\\        <effect name=\"dsp_fusion\" library=\"dsp_fusion\" uuid=\"$UUID\"/>" "$DEST"
        ;;
      *.conf)
        grep -q '^libraries[[:space:]]*{' "$DEST" || continue
        grep -q '^effects[[:space:]]*{' "$DEST" || continue
        sed -i '/^[[:space:]]*dsp_fusion[[:space:]]*{/,/}/d' "$DEST"
        sed -i '/^libraries[[:space:]]*{/a\  dsp_fusion {\n    path /vendor/lib/soundfx/libdspfusion.so\n  }' "$DEST"
        sed -i "/^effects[[:space:]]*{/a\\  dsp_fusion {\n    library dsp_fusion\n    uuid $UUID\n  }" "$DEST"
        ;;
    esac
    PATCHED=$((PATCHED + 1))
  done
done
[ "$PATCHED" -gt 0 ] || abort "No supported audio_effects config found; no changes were applied to the system."
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/action.sh" 0 0 0755
ui_print "Patched $PATCHED systemless audio configurations."
ui_print "Reboot, then install/open the DSP Fusion APK."
ui_print "Keep old JamesDSP / ViPER / AML modules disabled."
