"""Check a Fabric candidate's packaging, metadata and source inventory."""

import argparse
import json
from pathlib import Path, PurePosixPath
import zipfile


class FabricPackageError(ValueError):
    pass


def verify_candidate(manifest_path, target_id, source_root):
    from candidate_manifest import client_candidate
    from parity_evidence import digest

    manifest_path = Path(manifest_path)
    manifest, target, specification = client_candidate(manifest_path, target_id)
    version = manifest['release'][1:] + '+mc' + specification['minecraft'] + '-fabric'
    checks = verify_candidate_package(manifest_path.parent, target, specification,
                                      version, source_root)
    return {'target': target_id, 'manifest_sha256': digest(manifest_path),
            'source_commit': manifest['commit'], 'release': manifest['release'],
            'artifact_sha256': target['artifact']['sha256'],
            'sources_sha256': target['sources']['sha256'],
            'source_inventory_sha256': target['source_inventory']['sha256'],
            'checks': checks, 'releaseAcceptance': False}


def verify_candidate_package(base, target, specification, version, source_root):
    """Check one target after the manifest's catalog and selection are validated."""
    from artifact_checks import verify_artifact
    from parity_evidence import checked_file, read_json
    from source_package import verify_source_inventory

    if 'source_inventory' not in target:
        raise FabricPackageError('Candidate is missing its source inventory')
    artifact = checked_file(base, target['artifact'])
    sources = checked_file(base, target['sources'])
    inventory = checked_file(base, target['source_inventory'])
    return {
        'packaging': verify_artifact(artifact, sources, specification['java'],
                                    Path(source_root) / 'core/src/main/java'),
        'metadata': verify_fabric_metadata(artifact, specification, version),
        'sources': verify_source_inventory(sources, read_json(inventory), source_root)
    }


def verify_fabric_metadata(artifact, target, version):
    if target.get('loader') != 'fabric':
        raise FabricPackageError('Expected a Fabric target')
    with zipfile.ZipFile(artifact) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise FabricPackageError('Duplicate archive entry')

        def read_json(name):
            if not isinstance(name, str) or not name:
                raise FabricPackageError('Invalid resource name: ' + str(name))
            path = PurePosixPath(name)
            if path.is_absolute() or '..' in path.parts or name not in names:
                raise FabricPackageError('Missing or invalid resource: ' + name)
            try:
                value = json.loads(archive.read(name))
            except (ValueError, UnicodeError) as error:
                raise FabricPackageError('Invalid JSON: ' + name) from error
            if not isinstance(value, dict):
                raise FabricPackageError('Expected JSON object: ' + name)
            return value

        def check_class(name):
            if (not isinstance(name, str) or not name
                    or any(not part.isidentifier() for part in name.split('.'))):
                raise FabricPackageError('Invalid class name: ' + str(name))
            if name.replace('.', '/') + '.class' not in names:
                raise FabricPackageError('Missing declared class: ' + name)

        metadata = read_json('fabric.mod.json')
        expected = {'schemaVersion': 1, 'id': 'cbbg', 'version': version,
                    'environment': 'client'}
        for field, value in expected.items():
            if type(metadata.get(field)) is not type(value) or metadata[field] != value:
                raise FabricPackageError('Wrong Fabric metadata: ' + field)
        dependencies = {'fabricloader': '>=' + target['dependencies']['loader'],
                        'minecraft': target['minecraft'],
                        'java': '>=' + str(target['java']), 'fabric-api': '*'}
        if metadata.get('depends') != dependencies:
            raise FabricPackageError('Dependencies differ from target')
        entrypoints = metadata.get('entrypoints')
        if not isinstance(entrypoints, dict) or not entrypoints.get('client'):
            raise FabricPackageError('Missing client entrypoint')
        count = 0
        for kind, entries in entrypoints.items():
            if not isinstance(entries, list) or not entries:
                raise FabricPackageError('Invalid entrypoint list: ' + kind)
            for entry in entries:
                check_class(entry)
                count += 1
        mixins = metadata.get('mixins')
        if (not isinstance(mixins, list) or not mixins
                or any(not isinstance(name, str) for name in mixins)
                or len(mixins) != len(set(mixins))):
            raise FabricPackageError('Invalid mixin configuration list')
        for name in mixins:
            config = read_json(name)
            if config.get('required') is not True:
                raise FabricPackageError('Mixin configuration must be required: ' + name)
            if config.get('compatibilityLevel') != 'JAVA_' + str(target['java']):
                raise FabricPackageError('Mixin Java version differs from target: ' + name)
            package = config.get('package')
            if not isinstance(package, str) or not package:
                raise FabricPackageError('Missing mixin package: ' + name)
            declared = 0
            for section in ('client', 'mixins', 'server'):
                entries = config.get(section, [])
                if not isinstance(entries, list):
                    raise FabricPackageError('Invalid mixin class list: ' + name)
                for entry in entries:
                    if not isinstance(entry, str):
                        raise FabricPackageError('Invalid mixin class: ' + name)
                    check_class(package + '.' + entry)
                    declared += 1
            if not declared:
                raise FabricPackageError('Empty mixin configuration: ' + name)
            if 'plugin' in config:
                check_class(config['plugin'])
            if 'refmap' in config:
                read_json(config['refmap'])
            count += declared
        return {'declared_classes': count, 'mixin_configs': len(mixins)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--target', required=True)
    parser.add_argument('--source-root', type=Path, required=True,
                        help='Source checkout matching the candidate inventory')
    args = parser.parse_args()
    try:
        result = verify_candidate(args.candidate, args.target, args.source_root)
    except (ValueError, KeyError, TypeError, OSError, zipfile.BadZipFile) as error:
        parser.error(str(error))
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
