#!/usr/bin/env bash
# Synka katapult-scadas runtime (vite-bygget) in i APK:ns assets.
# Kör: tools/sync-runtime.sh [sökväg-till-katapult-scada]   (default ~/katapult-scada)
# Bygger runtimen (tsc + vite), kopierar index.html + assets/ och skriver
# RUNTIME_VERSION (scada-commit) som följer med i appens versionsnamn.
set -euo pipefail
SCADA="${1:-$HOME/katapult-scada}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$HERE/apk/app/src/main/assets/www"

echo "bygger runtimen i $SCADA/runtime"
( cd "$SCADA/runtime" && npm run build >/dev/null )

rm -rf "$DEST"
mkdir -p "$DEST/assets"
cp "$SCADA/runtime/dist/index.html" "$DEST/index.html"
# Bara runtimens filer — byggläget (editor-*) kräver config-servern och hör inte hemma på paddan.
for f in "$SCADA"/runtime/dist/assets/*; do
  case "$(basename "$f")" in
    editor-*) ;;
    *) cp "$f" "$DEST/assets/" ;;
  esac
done
git -C "$SCADA" rev-parse --short HEAD > "$DEST/RUNTIME_VERSION"
echo "runtime $(cat "$DEST/RUNTIME_VERSION") → $DEST"
ls -la "$DEST" "$DEST/assets"
