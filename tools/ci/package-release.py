#!/usr/bin/env python3
"""Verify the APK identity and certificate before preparing public release assets."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

# The metadata file has a hyphenated CLI name, so load only its shared definitions.
import importlib.util
spec = importlib.util.spec_from_file_location('release_metadata', Path(__file__).with_name('release-metadata.py'))
metadata = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metadata)
ROOT = metadata.ROOT


def run(*command: str) -> str:
    return subprocess.run(command, text=True, stdout=subprocess.PIPE, check=True).stdout


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    name, code = metadata.version()
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        raise SystemExit('ANDROID_HOME is not configured.')
    tools = Path(sdk) / 'build-tools/36.0.0'
    apk_directory = ROOT / 'app/build/outputs/apk/release'
    manifest = json.loads((apk_directory / 'output-metadata.json').read_text())
    if manifest.get('applicationId') != metadata.APPLICATION_ID or manifest.get('variantName') != 'release':
        raise SystemExit('Unexpected release APK output metadata.')
    expected = os.environ.get('ANDROID_SIGNING_CERT_SHA256', '').replace(':', '').lower()
    if not re.fullmatch(r'[a-f0-9]{64}', expected):
        raise SystemExit('Set repository variable ANDROID_SIGNING_CERT_SHA256 to the release certificate fingerprint.')
    supported_abis = {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}
    outputs = {}
    for element in manifest.get('elements', []):
        filters = element.get('filters', [])
        if not filters:
            abi = 'universal'
        elif len(filters) == 1 and filters[0].get('filterType') == 'ABI':
            abi = filters[0].get('value')
        else:
            raise SystemExit('Unsupported APK output filters.')
        if abi not in supported_abis | {'universal'} or abi in outputs:
            raise SystemExit('Unexpected or duplicate APK ABI in output metadata.')
        filename = element.get('outputFile', '')
        if not filename or Path(filename).name != filename or not filename.endswith('.apk'):
            raise SystemExit('Invalid APK output filename.')
        if element.get('versionCode') != code or element.get('versionName') != name:
            raise SystemExit('APK output metadata version does not match release metadata.')
        outputs[abi] = apk_directory / filename
    if set(outputs) != supported_abis | {'universal'}:
        raise SystemExit('Release requires four single-ABI APKs plus a universal APK; build with -PsplitApks=true.')
    target = ROOT / 'build/release'
    target.mkdir(parents=True, exist_ok=True)
    # Avoid attaching stale assets after a local build of a different version.
    for stale in target.iterdir():
        if stale.is_file():
            stale.unlink()
    apk_info = []
    for abi, apk in sorted(outputs.items()):
        verification = run(str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk))
        certificate = re.search(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]+)', verification)
        if not certificate or certificate.group(1) != expected or 'Number of signers: 1' not in verification:
            raise SystemExit(f'The {abi} APK signer does not match the expected release certificate.')
        run(str(tools / 'zipalign'), '-c', '-P', '16', '4', str(apk))
        badging = run(str(tools / 'aapt'), 'dump', 'badging', str(apk))
        identity = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
        if not identity or identity.groups() != (metadata.APPLICATION_ID, str(code), name):
            raise SystemExit(f'The {abi} APK package or version does not match release metadata.')
        if 'application-debuggable' in badging:
            raise SystemExit('Refusing to publish a debuggable APK.')
        with zipfile.ZipFile(apk) as archive:
            entries = archive.namelist()
            if any(entry.startswith('.signing/') or entry.endswith(('.keystore', '.jks')) or 'keystore.properties' in entry
                   for entry in entries):
                raise SystemExit('Signing material must not be packaged in the APK.')
            native_abis = {entry.split('/')[1] for entry in entries if entry.startswith('lib/') and entry.endswith('.so')}
            expected_abis = supported_abis if abi == 'universal' else {abi}
            if native_abis != expected_abis:
                raise SystemExit(f'Wrong native ABI contents in the {abi} APK.')
            for native_abi in expected_abis:
                for library in ('libmpv.so', 'libavcodec.so', 'libavformat.so'):
                    if f'lib/{native_abi}/{library}' not in entries:
                        raise SystemExit(f'Missing {library} for {native_abi}.')
        filename = f'media-player-{name}-{abi}.apk'
        shutil.copyfile(apk, target / filename)
        apk_info.append({'file': filename, 'abi': abi, 'size_bytes': apk.stat().st_size,
                         'sha256': file_sha256(apk)})
        print(f'Verified {abi}: {apk.stat().st_size / 1024**2:.2f} MiB, signature and alignment OK.')
    mapping = ROOT / 'app/build/outputs/mapping/release'
    if not (mapping / 'mapping.txt').is_file():
        raise SystemExit('Missing release R8 mapping.txt.')
    with zipfile.ZipFile(target / f'media-player-{name}-mapping.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for entry in sorted(mapping.glob('*.txt')):
            archive.write(entry, entry.name)
    info = {
        'application_id': metadata.APPLICATION_ID, 'version_name': name, 'version_code': code,
        'git_commit': run('git', '-C', str(ROOT), 'rev-parse', 'HEAD').strip(),
        'signing_certificate_sha256': expected,
        'github_run_id': os.environ.get('GITHUB_RUN_ID'),
        'apks': apk_info,
    }
    (target / 'BUILD_INFO.json').write_text(json.dumps(info, indent=2) + '\n')
    sums = ''.join(f'{file_sha256(entry)}  {entry.name}\n'
                   for entry in sorted(target.iterdir()) if entry.is_file())
    (target / 'SHA256SUMS').write_text(sums)
    notes = ROOT / f'docs/releases/{name}.md'
    if not notes.is_file():
        raise SystemExit(f'Missing release notes: docs/releases/{name}.md')
    shutil.copyfile(notes, target / 'RELEASE_NOTES.md')
    print(f'Verified release assets prepared in {target.relative_to(ROOT)}.')


if __name__ == '__main__':
    main()
