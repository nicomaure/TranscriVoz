"""
Genera el hash PBKDF2 para usar en .env como PASSWORD_HASH.
Uso: python3 generar_hash.py
"""
import hashlib
import getpass

while True:
    password = getpass.getpass("Ingresa la contraseña: ")
    password2 = getpass.getpass("Repeti la contraseña: ")
    if password == password2:
        break
    print("Las contraseñas no coinciden. Intenta de nuevo.\n")

h = hashlib.pbkdf2_hmac("sha256", password.encode(), b"transcriptor-groq", 100000).hex()
print(f"\nTu PASSWORD_HASH es:\n{h}")
print(f"\nAgregalo a .env asi:\nPASSWORD_HASH={h}")
