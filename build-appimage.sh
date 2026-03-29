#!/bin/bash
set -e

APP_NAME="TranscriVoz"
APP_VERSION="1.0.0"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="/tmp/transcrivoz-appimage-build"
APPDIR="${BUILD_DIR}/${APP_NAME}.AppDir"
PYTHON_VERSION="3.12"
PYTHON_APPIMAGE_URL="https://github.com/niess/python-appimage/releases/download/python3.12/python3.12.12-cp312-cp312-manylinux2014_x86_64.AppImage"

echo "=== Building ${APP_NAME} AppImage ==="

# Clean previous build
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"

# Step 1: Download Python AppImage (portable Python)
echo "[1/6] Downloading portable Python ${PYTHON_VERSION}..."
PYTHON_AI="${BUILD_DIR}/python.AppImage"
curl -L -o "${PYTHON_AI}" "${PYTHON_APPIMAGE_URL}"
chmod +x "${PYTHON_AI}"

# Step 2: Extract Python AppImage into our AppDir
echo "[2/6] Extracting Python..."
cd "${BUILD_DIR}"
"${PYTHON_AI}" --appimage-extract >/dev/null 2>&1
mv squashfs-root "${APPDIR}"

# Clean up Python AppImage artifacts (AppRun is a symlink, desktop file, etc.)
rm -f "${APPDIR}/AppRun"  # Remove symlink to usr/bin/python3.12
rm -f "${APPDIR}/python3.12.12.desktop"
rm -f "${APPDIR}/python3.12.12.svg" "${APPDIR}/python3.12.12.png" 2>/dev/null
rm -f "${APPDIR}/python.png" 2>/dev/null
rm -f "${APPDIR}/usr/share/metainfo/python3.12.12.appdata.xml" 2>/dev/null
rm -f "${APPDIR}/usr/bin/python" "${APPDIR}/usr/bin/python3" "${APPDIR}/usr/bin/python3.12"
rm -f "${APPDIR}/.DirIcon" 2>/dev/null

# Step 3: Install Python dependencies
echo "[3/6] Installing Python packages..."
"${APPDIR}/opt/python${PYTHON_VERSION}/bin/python${PYTHON_VERSION}" -m pip install --no-warn-script-location \
    --upgrade pip 2>&1 | tail -1
"${APPDIR}/opt/python${PYTHON_VERSION}/bin/python${PYTHON_VERSION}" -m pip install --no-warn-script-location \
    flask groq openai python-dotenv yt-dlp gdown 2>&1 | tail -1

# Step 4: Copy app files
echo "[4/6] Copying TranscriVoz files..."
APP_DEST="${APPDIR}/opt/transcrivoz"
mkdir -p "${APP_DEST}"
cp "${SCRIPT_DIR}/app.py" "${APP_DEST}/"
cp "${SCRIPT_DIR}/main.py" "${APP_DEST}/"
cp -r "${SCRIPT_DIR}/templates" "${APP_DEST}/"

# Step 5: Download static ffmpeg
echo "[5/6] Downloading ffmpeg..."
FFMPEG_URL="https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz"
curl -L -o "${BUILD_DIR}/ffmpeg.tar.xz" "${FFMPEG_URL}"
cd "${BUILD_DIR}"
tar xf ffmpeg.tar.xz
FFMPEG_DIR=$(ls -d ffmpeg-*-amd64-static 2>/dev/null | head -1)
cp "${BUILD_DIR}/${FFMPEG_DIR}/ffmpeg" "${APPDIR}/usr/bin/"
cp "${BUILD_DIR}/${FFMPEG_DIR}/ffprobe" "${APPDIR}/usr/bin/"

# Step 6: Create AppRun and desktop integration
echo "[6/6] Creating AppImage metadata..."

cat > "${APPDIR}/AppRun" << 'APPRUN'
#!/bin/bash
SELF="$(readlink -f "$0")"
APPDIR="${SELF%/*}"

export PATH="${APPDIR}/usr/bin:${PATH}"
export LD_LIBRARY_PATH="${APPDIR}/opt/python3.12/lib:${APPDIR}/usr/lib:${LD_LIBRARY_PATH}"
export PYTHONHOME="${APPDIR}/opt/python3.12"
export PYTHONDONTWRITEBYTECODE=1
export SSL_CERT_FILE="${APPDIR}/opt/_internal/certs.pem"
export TRANSCRIVOZ_DESKTOP=1

exec "${APPDIR}/opt/python3.12/bin/python3.12" "${APPDIR}/opt/transcrivoz/main.py" "$@"
APPRUN
chmod +x "${APPDIR}/AppRun"

# Desktop file
cat > "${APPDIR}/transcrivoz.desktop" << DESKTOP
[Desktop Entry]
Type=Application
Name=TranscriVoz
Comment=Transcriptor de audio/video usando IA
Exec=TranscriVoz
Icon=transcrivoz
Categories=AudioVideo;Audio;Utility;
Terminal=false
DESKTOP

# Simple SVG icon
cat > "${APPDIR}/transcrivoz.svg" << 'SVG'
<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128" width="128" height="128">
  <rect width="128" height="128" rx="20" fill="#1a1a2e"/>
  <circle cx="64" cy="52" r="22" fill="none" stroke="#50e6ff" stroke-width="4"/>
  <rect x="58" y="34" width="12" height="36" rx="6" fill="#0078d4"/>
  <line x1="64" y1="78" x2="64" y2="96" stroke="#50e6ff" stroke-width="4" stroke-linecap="round"/>
  <line x1="48" y1="96" x2="80" y2="96" stroke="#50e6ff" stroke-width="4" stroke-linecap="round"/>
</svg>
SVG

# .DirIcon symlink
ln -sf transcrivoz.svg "${APPDIR}/.DirIcon"

# Build AppImage
echo "=== Packaging AppImage ==="
cd "${BUILD_DIR}"

# Download appimagetool if not present
if [ ! -f /tmp/appimagetool ]; then
    echo "Downloading appimagetool..."
    curl -L -o /tmp/appimagetool https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
    chmod +x /tmp/appimagetool
fi

ARCH=x86_64 /tmp/appimagetool "${APPDIR}" "${SCRIPT_DIR}/${APP_NAME}-${APP_VERSION}-x86_64.AppImage"

echo ""
echo "=== Done! ==="
echo "AppImage: ${SCRIPT_DIR}/${APP_NAME}-${APP_VERSION}-x86_64.AppImage"
echo "Size: $(du -h "${SCRIPT_DIR}/${APP_NAME}-${APP_VERSION}-x86_64.AppImage" | cut -f1)"
