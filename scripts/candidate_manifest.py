"""Read a client candidate and check its selected target and catalog."""

from pathlib import Path

from parity_evidence import (EvidenceError, catalog_digest, checked_file, read_json,
                             selected_target_specs, unique_by, validate_release_identity)
from targets import load_catalog


def client_candidate(manifest_path, target_id):
    manifest_path = Path(manifest_path)
    manifest = read_json(manifest_path)
    if type(manifest.get('schema')) is not int or manifest['schema'] != 2:
        raise EvidenceError('Client validation requires candidate schema 2')
    validate_release_identity(manifest['release'], manifest['commit'])
    targets = unique_by(manifest['targets'], lambda item: item['id'], 'candidate target')
    if target_id not in targets:
        raise EvidenceError('Target is absent from candidate')
    target = targets[target_id]
    base = manifest_path.parent
    catalog = load_catalog(checked_file(base, target['client_tests']['catalog']))
    selected = selected_target_specs(catalog, manifest['selected_targets'])
    if targets.keys() != selected.keys() or manifest['catalog_sha256'] != catalog_digest(catalog):
        raise EvidenceError('Candidate catalog or target selection differs')
    for identifier, specification in selected.items():
        owner = specification.get('artifactOf')
        if owner is not None:
            for kind in ('artifact', 'sources'):
                if targets[identifier][kind]['sha256'] != targets[owner][kind]['sha256']:
                    raise EvidenceError('Shared ' + kind + ' differs from owner: ' + identifier)
    for kind in ('artifact', 'sources'):
        checked_file(base, target[kind])
    if 'source_inventory' in target:
        checked_file(base, target['source_inventory'])
    return manifest, target, selected[target_id]
