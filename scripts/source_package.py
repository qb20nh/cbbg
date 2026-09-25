"""Verify packaged Java sources against the build's source inventory."""

import hashlib
from pathlib import Path, PurePosixPath
import re
import zipfile


class SourcePackageError(ValueError):
    pass


def relative_path(value):
    if not isinstance(value, str) or not value or '\\' in value:
        raise SourcePackageError('Invalid source path: ' + str(value))
    path = PurePosixPath(value)
    if path.is_absolute() or '..' in path.parts or str(path) != value:
        raise SourcePackageError('Invalid source path: ' + value)
    if path.suffix != '.java':
        raise SourcePackageError('Expected Java source: ' + value)
    return path


def verify_source_inventory(sources, inventory, source_root):
    if (not isinstance(inventory, dict) or type(inventory.get('schema')) is not int
            or inventory['schema'] != 1):
        raise SourcePackageError('Invalid source inventory schema')
    entries = inventory.get('sources')
    if not isinstance(entries, list) or not entries:
        raise SourcePackageError('Empty source inventory')
    root = Path(source_root).resolve()
    expected = {}
    source_paths = set()
    for entry in entries:
        if not isinstance(entry, dict):
            raise SourcePackageError('Invalid source inventory entry')
        archive_path = str(relative_path(entry.get('archive_path')))
        source_path = str(relative_path(entry.get('source_path')))
        digest = entry.get('sha256')
        if not isinstance(digest, str) or not re.fullmatch('[0-9a-f]{64}', digest):
            raise SourcePackageError('Invalid source hash: ' + archive_path)
        if archive_path in expected or source_path in source_paths:
            raise SourcePackageError('Duplicate source inventory entry: ' + archive_path)
        path = (root / source_path).resolve()
        if not path.is_relative_to(root) or not path.is_file():
            raise SourcePackageError('Missing or invalid source file: ' + source_path)
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise SourcePackageError('Source file differs from inventory: ' + source_path)
        expected[archive_path] = digest
        source_paths.add(source_path)
    with zipfile.ZipFile(sources) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise SourcePackageError('Duplicate source archive entry')
        actual = {name for name in names if name.endswith('.java')}
        if actual != expected.keys():
            raise SourcePackageError('Source archive differs from inventory; missing='
                                     + str(sorted(expected.keys() - actual))
                                     + '; unexpected=' + str(sorted(actual - expected.keys())))
        for name, digest in expected.items():
            if hashlib.sha256(archive.read(name)).hexdigest() != digest:
                raise SourcePackageError('Packaged source differs from inventory: ' + name)
    return {'java_sources': len(expected)}
