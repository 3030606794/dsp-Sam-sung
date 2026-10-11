#!/system/bin/sh
SKIPUNZIP=0
# Empty on Android; host fixtures replace only this assignment in a temporary copy.
CONFIG_BASE=""
UUID=30306067-9400-4d53-9000-000000000001

patch_xml() {
  # Tokenize XML rather than removing whole lines, preserving minified/multiline tags.
  awk -v uuid="$UUID" '
  function fail() { bad=1; exit 2 }
  function attr(tag, key,    s,q,e) {
    if (!match(tag,"[[:space:]]" key "[[:space:]]*=[[:space:]]*")) return ""
    s=substr(tag,RSTART+RLENGTH); q=substr(s,1,1)
    if (q!="\"" && q!=sprintf("%c",39)) return ""
    s=substr(s,2); e=index(s,q); return e ? substr(s,1,e-1) : ""
  }
  { data=data $0 "\n" }
  END {
    out=""; depth=0; skip=0; libs=0; effects=0; roots=0
    while (length(data)) {
      p=index(data,"<")
      if (!p) { if (!skip) out=out data; break }
      if (!skip) out=out substr(data,1,p-1)
      data=substr(data,p)
      if (substr(data,1,4)=="<!--") {
        e=index(data,"-->"); if (!e) fail(); e+=2
        if (!skip) out=out substr(data,1,e)
        data=substr(data,e+1); continue
      }
      q=""; e=0
      for (j=2;j<=length(data);j++) {
        c=substr(data,j,1)
        if (q!="") { if (c==q) q="" }
        else if (c=="\"" || c==sprintf("%c",39)) q=c
        else if (c==">") { e=j; break }
      }
      if (!e) fail()
      tag=substr(data,1,e); data=substr(data,e+1)
      if (tag ~ /^<\?/) { if (!skip) out=out tag; continue }
      if (tag ~ /^<!/) fail()
      closing=(tag ~ /^<\//); self=(tag ~ /\/[[:space:]]*>$/)
      name=tag; sub(/^<\/?[[:space:]]*/,"",name); sub(/[[:space:]\/>].*$/,"",name)
      if (name=="") fail()
      if (closing) {
        if (depth==0 || stack[depth]!=name) fail()
        if (!skip) out=out tag
        if (skip==depth) skip=0
        delete stack[depth--]; continue
      }
      if (depth==0) { roots++; if (roots>1) fail() }
      parent=stack[depth]
      own=((name=="library" && parent=="libraries") || (name=="effect" && parent=="effects")) && attr(tag,"name")=="dsp_fusion"
      if (own && !self) skip=depth+1
      if (!own && !skip) {
        if (name=="libraries" || name=="effects") {
          if (depth!=1) fail()
          if (name=="libraries") {
            libs++; insert="\n        <library name=\"dsp_fusion\" path=\"libdspfusion.so\"/>"
          } else {
            effects++; insert="\n        <effect name=\"dsp_fusion\" library=\"dsp_fusion\" uuid=\"" uuid "\"/>"
          }
          if (self) { sub(/\/[[:space:]]*>$/,">",tag); out=out tag insert "\n    </" name ">" }
          else out=out tag insert
        } else out=out tag
      }
      if (!self) stack[++depth]=name
    }
    if (depth || skip || libs!=1 || effects!=1 || roots!=1) exit 2
    printf "%s",out
  }' "$1" > "$2"
}

patch_conf() {
  # Remove only the Fusion blocks at the correct nesting depth. Vendor effects,
  # comments and quoted paths remain intact, including configs on a single line.
  awk -v uuid="$UUID" -v libpath="$LIBPATH" '
  { data=data $0 "\n" }
  END {
    n=length(data); depth=0; libs=0; effects=0; bad=0; out=""; last=1; previous=""
    for (i=1;i<=n;) {
      c=substr(data,i,1)
      if (c ~ /[[:space:]]/) { i++; continue }
      if (c=="#" || substr(data,i,2)=="//") {
        e=index(substr(data,i),"\n"); i=e ? i+e : n+1; continue
      }
      if (c=="\"" || c==sprintf("%c",39)) {
        q=c; start=i++
        while (i<=n && substr(data,i,1)!=q) { if (substr(data,i,1)=="\\") i++; i++ }
        if (i>n) { bad=1; break }; i++; previous=substr(data,start,i-start); prevpos=start; continue
      }
      if (c=="{") {
        if (previous=="") { bad=1; break }
        parent=stack[depth]; name=previous; stack[++depth]=name
        if (depth==1 && (name=="libraries" || name=="effects")) {
          if (name=="libraries") { libs++; add="\n  dsp_fusion {\n    path " libpath "\n  }\n" }
          else { effects++; add="\n  dsp_fusion {\n    library dsp_fusion\n    uuid " uuid "\n  }\n" }
          out=out substr(data,last,i-last+1) add; last=i+1
        } else if (depth==2 && name=="dsp_fusion" && (parent=="libraries" || parent=="effects")) {
          out=out substr(data,last,prevpos-last); last=prevpos; skip=depth
        }
        previous=""; i++; continue
      }
      if (c=="}") {
        if (depth==0) { bad=1; break }
        if (skip==depth) { last=i+1; skip=0 }
        delete stack[depth--]; previous=""; i++; continue
      }
      start=i
      while (i<=n && substr(data,i,1)!~ /[[:space:]{}#]/) i++
      previous=substr(data,start,i-start); prevpos=start
    }
    if (bad || depth || skip || libs!=1 || effects!=1) exit 2
    out=out substr(data,last); printf "%s",out
  }' "$1" > "$2"
}

ui_print "DSP Fusion 0.1.0-alpha2"
ui_print "One module / one effect / one application"
[ "$API" -ge 26 ] || abort "Android 8.0 or newer is required."
case "$ARCH" in
  arm64) LIBPATH=/vendor/lib64/soundfx/libdspfusion.so ;;
  arm) LIBPATH=/vendor/lib/soundfx/libdspfusion.so; rm -rf "$MODPATH/system/vendor/lib64" ;;
  *) abort "This build supports ARM and ARM64 only." ;;
esac
[ -f "$MODPATH/system/vendor/lib/soundfx/libdspfusion.so" ] || abort "32-bit driver is missing from the ZIP."
if [ "$ARCH" = arm64 ]; then
  [ -f "$MODPATH/system/vendor/lib64/soundfx/libdspfusion.so" ] || abort "64-bit driver is missing from the ZIP."
fi

for OLD in ainur_jamesdsp ViPER4Android-RE-Fork ViPER4Android-RE-AIDL aml; do
  OLD_DIR="$CONFIG_BASE/data/adb/modules/$OLD"
  if [ -d "$OLD_DIR" ] && [ ! -f "$OLD_DIR/disable" ] && [ ! -f "$OLD_DIR/remove" ]; then
    abort "Disable $OLD in Magisk and reboot before installing DSP Fusion."
  fi
done

# Build private systemless copies. Never edit the live vendor files.
PATCHED=0
LIST="$MODPATH/.fusion-config-list"
TMP="$MODPATH/.fusion-config-patched"
: > "$LIST"
# Magisk maps vendor/product/system_ext through system/. Independent ODM mounts
# are not assumed; refuse an ODM-only device instead of claiming a working install.
for ROOT in /vendor/etc /system/etc /product/etc /system_ext/etc; do
  [ -d "$CONFIG_BASE$ROOT" ] || continue
  find -L "$CONFIG_BASE$ROOT" -type f \( -name '*audio_effects*.xml' -o -name '*audio_effects*.conf' \) >> "$LIST" 2>/dev/null
done
while IFS= read -r SRC; do
  LOGICAL="${SRC#"$CONFIG_BASE"}"
  case "$LOGICAL" in *haptic*) continue ;; esac
  # Disabled modules remain mounted until reboot. Refuse stale registrations
  # instead of carrying references to soon-to-disappear old libraries forward.
  if grep -Eiq 'f27317f4-c984-4de6-9a90-545759495bf2|90380da3-8536-4744-a6a3-5731970e640f|41d3c987-e6cf-11e3-a88a-11aba5d5c51b' "$SRC"; then
    rm -f "$LIST" "$TMP"
    abort "Old JamesDSP / ViPER registration remains in $LOGICAL. Disable old modules and AML, reboot, then install again."
  fi
  case "$LOGICAL" in
    /system/*) DEST="$MODPATH$LOGICAL" ;;
    *) DEST="$MODPATH/system$LOGICAL" ;;
  esac
  case "$SRC" in
    *.xml) patch_xml "$SRC" "$TMP" ;;
    *.conf) patch_conf "$SRC" "$TMP" ;;
  esac
  if [ "$?" -ne 0 ]; then
    rm -f "$TMP"
    ui_print "Skipped unsupported audio configuration: $LOGICAL"
    continue
  fi
  mkdir -p "${DEST%/*}" || abort "Cannot create destination for $LOGICAL"
  mv -f "$TMP" "$DEST" || abort "Cannot write private copy of $LOGICAL"
  PATCHED=$((PATCHED + 1))
done < "$LIST"
rm -f "$LIST" "$TMP"
[ "$PATCHED" -gt 0 ] || abort "No supported audio_effects config found; no changes were applied to the system."
set_perm_recursive "$MODPATH" 0 0 0755 0644
# Vendor audio HALs cannot load libraries labelled system_file on enforcing ROMs.
# Apply partition labels after the generic module permissions; bind mounts retain
# the source inode's label. This replaces the relabelling previously done by AML.
set_perm_recursive "$MODPATH/system/vendor" 0 0 0755 0644 u:object_r:vendor_file:s0 || abort "Cannot label the vendor audio driver."
if [ -d "$MODPATH/system/vendor/etc" ]; then
  set_perm_recursive "$MODPATH/system/vendor/etc" 0 2000 0755 0644 u:object_r:vendor_configs_file:s0 || abort "Cannot label the vendor audio configurations."
fi
set_perm "$MODPATH/action.sh" 0 0 0755
ui_print "Patched $PATCHED systemless audio configurations."
ui_print "Reboot, then install/open the DSP Fusion APK."
ui_print "Keep old JamesDSP / ViPER / AML modules disabled."
