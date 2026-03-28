#!/bin/bash
# Setup automatico de TranscriVoz
# Funciona en Linux, macOS, y WSL

set -e

echo ""
echo "==================================="
echo "  TranscriVoz - Setup"
echo "==================================="
echo ""

# Check Python
if ! command -v python3 &> /dev/null; then
    echo "ERROR: Python 3 no esta instalado."
    echo "Instala con: sudo apt install python3 python3-venv"
    exit 1
fi

# Check ffmpeg
if ! command -v ffmpeg &> /dev/null; then
    echo "ERROR: ffmpeg no esta instalado."
    echo "Instala con: sudo apt install ffmpeg"
    exit 1
fi

echo "[1/4] Creando entorno virtual..."
python3 -m venv venv
source venv/bin/activate

echo "[2/4] Instalando dependencias..."
pip install -r requirements.txt -q

echo "[3/4] Configurando .env..."
if [ ! -f .env ]; then
    # Generate SECRET_KEY
    SECRET=$(python3 -c "import os; print(os.urandom(32).hex())")

    # Ask for API key
    echo ""
    echo "Necesitas una API key de Groq (gratis)."
    echo "Conseguila en: https://console.groq.com -> API Keys"
    echo ""
    read -p "Pega tu API key (gsk_...): " API_KEY

    # Ask for password with confirmation
    echo ""
    while true; do
        read -s -p "Elige una contraseña para la app: " PASSWORD
        echo ""
        read -s -p "Repeti la contraseña: " PASSWORD2
        echo ""
        if [ "$PASSWORD" = "$PASSWORD2" ]; then
            break
        else
            echo "Las contraseñas no coinciden. Intenta de nuevo."
            echo ""
        fi
    done
    HASH=$(python3 -c "import hashlib; print(hashlib.pbkdf2_hmac('sha256', b'$PASSWORD', b'transcriptor-groq', 100000).hex())")

    cat > .env << EOF
GROQ_API_KEY=$API_KEY
PASSWORD_HASH=$HASH
SECRET_KEY=$SECRET
EOF

    echo "Archivo .env creado."
else
    echo "Archivo .env ya existe, no se modifica."
fi

echo "[4/4] Listo!"
echo ""
echo "==================================="
echo "  Para iniciar:"
echo "  source venv/bin/activate"
echo "  python3 app.py"
echo ""
echo "  Abrir en: http://localhost:5050"
echo "==================================="
echo ""
