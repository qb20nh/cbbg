"""Check the catalog-selected external mod jars before a local Fabric probe."""

import hashlib
from pathlib import Path


def verify_dependencies(target, profile, paths, lock):
    if lock.get('schemaVersion') != 1 or lock.get('target') != target['id']:
        raise ValueError('Dependency lock target/schema mismatch')
    if profile not in target['compatibilityProfiles']:
        raise ValueError('Unknown compatibility profile')
    aliases = {name.lower(): name for name in lock['dependencies']}
    selected = set() if profile == 'none' else set(profile.split('+'))
    if 'iris' in selected:
        selected.add('sodium')
    if 'renderscale' in selected:
        selected.add('clothconfig')
    selected.add('fabricapi')
    if not selected <= aliases.keys():
        raise ValueError('Missing dependency lock entries')
    required = {aliases[name] for name in selected}
    if set(paths) != required:
        raise ValueError('Missing or extra selected mod dependencies')
    for name in sorted(required):
        entry = lock['dependencies'][name]
        if entry['pin'] != target['dependencies'].get(name):
            raise ValueError('Dependency lock catalog pin mismatch: ' + name)
        with Path(paths[name]).open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        if digest != entry['sha256']:
            raise ValueError('Dependency checksum mismatch: ' + name)
