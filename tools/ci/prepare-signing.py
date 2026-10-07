#!/usr/bin/env python3
"""Restore repository signing secrets without logging their values."""
import base64
import os
from pathlib import Path


def property_value(value: str) -> str:
    # Properties.load(InputStream) uses Latin-1 and Java property escapes.
    escaped = []
    for character in value:
        if character.isascii() and character.isalnum():
            escaped.append(character)
        else:
            encoded = character.encode('utf-16-be')
            for index in range(0, len(encoded), 2):
                escaped.append(f'\\u{int.from_bytes(encoded[index:index + 2], "big"):04x}')
    return ''.join(escaped)


def main() -> None:
    required = ('ANDROID_KEYSTORE_BASE64', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD')
    missing = [name for name in required if not os.environ.get(name)]
    if missing:
        raise SystemExit('Missing repository secrets: ' + ', '.join(missing))
    root = Path(__file__).resolve().parents[2]
    directory = root / '.signing'
    key = directory / 'media-player-release.keystore'
    properties = root / 'keystore.properties'
    if key.exists() or properties.exists():
        raise SystemExit('Signing files already exist; refusing to overwrite.')
    try:
        data = base64.b64decode(os.environ['ANDROID_KEYSTORE_BASE64'], validate=True)
    except ValueError:
        raise SystemExit('ANDROID_KEYSTORE_BASE64 is not valid base64.') from None
    if not data:
        raise SystemExit('The signing keystore is empty.')
    os.umask(0o077)
    directory.mkdir(mode=0o700, exist_ok=True)
    directory.chmod(0o700)
    with key.open('xb') as stream:
        stream.write(data)
    values = {
        'storeFile': '.signing/media-player-release.keystore',
        'storeType': 'PKCS12',
        'storePassword': os.environ['ANDROID_KEYSTORE_PASSWORD'],
        'keyAlias': os.environ['ANDROID_KEY_ALIAS'],
        'keyPassword': os.environ['ANDROID_KEY_PASSWORD'],
    }
    with properties.open('x', encoding='ascii') as stream:
        for name, value in values.items():
            stream.write(f'{name}={property_value(value)}\n')
    print('Release signing files restored with owner-only permissions.')


if __name__ == '__main__':
    main()
