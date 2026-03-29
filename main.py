#!/usr/bin/env python3
"""TranscriVoz Desktop — entry point for AppImage and Windows builds.

Launches Flask on a local port and opens the default browser.
No password required in desktop mode.
"""

import os
import sys
import socket
import threading
import webbrowser
import time

# Set desktop mode BEFORE importing app
os.environ["TRANSCRIVOZ_DESKTOP"] = "1"

# PyInstaller: add bundled binaries (ffmpeg, ffprobe) to PATH
if getattr(sys, 'frozen', False):
    bundle_dir = sys._MEIPASS
    os.environ["PATH"] = bundle_dir + os.pathsep + os.environ.get("PATH", "")
    # Tell Flask where templates are
    os.environ["TRANSCRIVOZ_TEMPLATE_DIR"] = os.path.join(bundle_dir, "templates")


def find_free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def main():
    port = find_free_port()
    url = f"http://127.0.0.1:{port}"

    # Import app after setting env
    from app import app

    # Start Flask in a background thread
    server_thread = threading.Thread(
        target=lambda: app.run(host="127.0.0.1", port=port, debug=False, use_reloader=False),
        daemon=True,
    )
    server_thread.start()

    # Wait for Flask to be ready
    time.sleep(0.5)

    # Open in default browser
    print(f"TranscriVoz iniciado en {url}")
    print("Presiona Ctrl+C para cerrar")
    webbrowser.open(url)

    # Keep the process alive
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nTranscriVoz cerrado")


if __name__ == "__main__":
    main()
