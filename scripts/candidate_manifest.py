"""Read a client candidate and check its selected target and catalog."""

from pathlib import Path

from parity_evidence import (EvidenceError, catalog_digest, checked_file, read_json,
                             selected_target_specs, unique_by, validate_release_identity)
from runtime_catalog import load_catalog


def client_candidate(manifest_path, target_id):
    manifest_path = Path(manifest_path)
    manifest = read_json(manifest_path)
    if type(manifest.get('schema')) is not int or manifest['schema'] not in (2, 3):
        raise EvidenceError('Client validation requires candidate schema 2 or 3')
    validate_release_identity(manifest['release'], manifest['commit'])
    targets = unique_by(manifest['targets'], lambda item: item['id'], 'candidate target')
    if target_id not in targets:
        raise EvidenceError('Target is absent from candidate')
    target = targets[target_id]
    for record in targets.values():
        if manifest['schema'] == 3:
            if (not isinstance(record.get('mapping'), dict)
                    or record.get('processing') != {'tool': 'proguard', 'version': '7.10.0'}):
                raise EvidenceError('Processed candidate requires a mapping and supported ProGuard version')
        elif 'mapping' in record or 'processing' in record:
            raise EvidenceError('Processed candidates require schema 3')
    base = manifest_path.parent
    catalog = load_catalog(checked_file(base, target['client_tests']['catalog']))
    selected = selected_target_specs(catalog, manifest['selected_targets'])
    if targets.keys() != selected.keys() or manifest['catalog_sha256'] != catalog_digest(catalog):
        raise EvidenceError('Candidate catalog or target selection differs')
    for identifier, specification in selected.items():
        owner = specification.get('artifactOf')
        if owner is not None:
            for kind in ('artifact', 'sources') + (('mapping',) if manifest['schema'] == 3 else ()):
                if targets[identifier][kind]['sha256'] != targets[owner][kind]['sha256']:
                    raise EvidenceError('Shared ' + kind + ' differs from owner: ' + identifier)
    for kind in ('artifact', 'sources'):
        checked_file(base, target[kind])
    if 'source_inventory' in target:
        checked_file(base, target['source_inventory'])
    if manifest['schema'] == 3:
        checked_file(base, target['mapping'])
    return manifest, target, selected[target_id]
