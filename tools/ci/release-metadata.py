#!/usr/bin/env python3
"""Reject mismatched tags and emit metadata shared by build and publish jobs."""
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
APPLICATION_ID = 'io.github.micro123.mediaplayer'


def version() -> tuple[str, int]:
    properties = dict(line.split('=', 1) for line in (ROOT / 'gradle.properties').read_text().splitlines()
                      if '=' in line and not line.lstrip().startswith('#'))
    name = properties['appVersionName'].strip()
    code = int(properties['appVersionCode'].strip())
    if not re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(?:alpha|beta|rc)\.[1-9]\d*)?', name) or code < 1:
        raise SystemExit('Invalid appVersionName or appVersionCode in gradle.properties.')
    return name, code


def main() -> None:
    name, code = version()
    tag = f'v{name}'
    if os.environ.get('GITHUB_REF') != f'refs/tags/{tag}':
        raise SystemExit(f'Release must run on tag {tag}, matching gradle.properties.')
    subprocess.run(['git', 'merge-base', '--is-ancestor', 'HEAD', 'origin/main'], cwd=ROOT, check=True)
    values = {'version': name, 'version_code': str(code), 'tag': tag,
              'prerelease': str('-' in name).lower(),
              'draft': str(not (ROOT / 'LICENSE').is_file()).lower()}
    output = os.environ.get('GITHUB_OUTPUT')
    if output:
        with open(output, 'a') as stream:
            for key, value in values.items():
                stream.write(f'{key}={value}\n')
    print(json.dumps(values))


if __name__ == '__main__':
    main()
