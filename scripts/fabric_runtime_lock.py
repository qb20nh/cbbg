"""Fingerprint installed Fabric launch inputs; does not establish runtime acceptance."""

import argparse
import hashlib
from importlib.metadata import version
import json
import os
from pathlib import Path
import re


def capture_runtime(runtime, profile, command, *, exclude_profile_timestamps=True):
    runtime = Path(runtime).absolute()

    def relative(path):
        # Keep logical paths through shared library symlinks, but reject paths
        # outside the runtime rather than recording arbitrary host files.
        return Path(os.path.abspath(path)).relative_to(runtime).as_posix()

    if command.count('-cp') != 1:
        raise ValueError('Expected exactly one launch classpath')
    classpath = command[command.index('-cp') + 1].split(os.pathsep)
    if not classpath or any(not path for path in classpath):
        raise ValueError('Empty launch classpath')
    classpath = [relative(path) for path in classpath]
    files = set(classpath)
    profiles = []
    current = profile
    while current is not None:
        if (not isinstance(current, str) or not re.fullmatch(r'[A-Za-z0-9_.+\-]+', current)
                or current in ('.', '..') or current in profiles):
            raise ValueError('Invalid or cyclic profile inheritance')
        profiles.append(current)
        name = f'versions/{current}/{current}.json'
        metadata = json.loads((runtime / name).read_text(encoding='utf-8'))
        if metadata.get('id') != current:
            raise ValueError('Profile identity mismatch')
        files.add(name)
        current = metadata.get('inheritsFrom')

    native_directories = [relative(arg.split('=', 1)[1]) for arg in command
                          if arg.startswith('-Djava.library.path=')]
    if len(native_directories) != 1:
        raise ValueError('Expected exactly one native library directory')
    for directory in native_directories:
        files.update(relative(path) for path in (runtime / directory).rglob('*') if path.is_file())
    digests = {}
    for name in sorted(files):
        if exclude_profile_timestamps and name == f'versions/{profile}/{profile}.json':
            metadata = json.loads((runtime / name).read_text(encoding='utf-8'))
            metadata.pop('time', None)
            metadata.pop('releaseTime', None)
            content = json.dumps(metadata, sort_keys=True, separators=(',', ':')).encode('utf-8')
            digests[name] = hashlib.sha256(content).hexdigest()
        else:
            with (runtime / name).open('rb') as stream:
                digests[name] = hashlib.file_digest(stream, 'sha256').hexdigest()
    lock = {'schemaVersion': 1, 'scope': 'classpath-profiles-native-libraries',
            'profile': profile, 'profiles': profiles, 'classpath': classpath,
            'nativeDirectories': native_directories, 'files': digests}
    if exclude_profile_timestamps:
        lock['profileTimestampFieldsExcluded'] = ['time', 'releaseTime']
    return lock


def verify_runtime(runtime, profile, command, expected):
    policy = expected.get('profileTimestampFieldsExcluded')
    if policy not in (None, ['time', 'releaseTime']):
        raise ValueError('Unknown profile hash policy')
    if capture_runtime(runtime, profile, command,
                       exclude_profile_timestamps=policy is not None) != expected:
        raise ValueError('Runtime inputs differ from the recorded lock')


def fabric_command(runtime, profile):
    if version('minecraft-launcher-lib') != '8.0':
        raise ValueError('Use minecraft-launcher-lib==8.0 in an isolated environment')
    from minecraft_launcher_lib.command import get_minecraft_command
    # This is only command resolution. Never persist authentication arguments.
    return get_minecraft_command(profile, str(Path(runtime).absolute()), {
        'username': 'CbbgParity', 'uuid': '00000000000000000000000000000001', 'token': '0'})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--profile', required=True)
    parser.add_argument('--lock', type=Path, required=True)
    parser.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    command = fabric_command(args.runtime, args.profile)
    if args.verify:
        verify_runtime(args.runtime, args.profile, command,
                       json.loads(args.lock.read_text(encoding='utf-8')))
        print('Runtime lock verified')
    else:
        lock = capture_runtime(args.runtime, args.profile, command)
        with args.lock.open('x', encoding='utf-8') as stream:
            stream.write(json.dumps(lock, indent=2) + '\n')
        print(f'Recorded {len(lock["files"])} runtime inputs; runtime acceptance remains separate')


if __name__ == '__main__':
    main()
