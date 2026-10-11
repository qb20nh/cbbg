"""Read a client candidate and check its selected target and catalog."""

from pathlib import Path
import re

from parity_evidence import (EvidenceError, catalog_digest, checked_file, read_json,
                             selected_target_specs, unique_by, validate_release_identity,
                             validate_release_targets)
from runtime_catalog import load_catalog


def client_candidate(manifest_path, target_id):
    manifest_path = Path(manifest_path)
    manifest = read_json(manifest_path)
    if type(manifest.get('schema')) is not int or manifest['schema'] not in (2, 3):
        raise EvidenceError('Client validation requires candidate schema 2 or 3')
    validate_release_identity(manifest['release'], manifest['commit'])
    product = 'lib' if manifest['release'].startswith('lib/') else 'cbbg'
    if manifest.get('product', 'cbbg') != product:
        raise EvidenceError('Candidate product differs from release tag')
    targets = unique_by(manifest['targets'], lambda item: item['id'], 'candidate target')
    if target_id not in targets:
        raise EvidenceError('Target is absent from candidate')
    target = targets[target_id]
    base = manifest_path.parent
    for record in targets.values():
        contract = record.get('client_tests', {}).get('contract')
        if contract is not None:
            contract_product = read_json(checked_file(base, contract)).get('product', 'cbbg')
            if contract_product != product:
                raise EvidenceError('Candidate product requires its own acceptance contract')
        if ('utilities' in record) != ('utilities_sources' in record):
            raise EvidenceError('Candidate requires utilities and utilities_sources together')
        if ('library' in record) != ('library_sources' in record):
            raise EvidenceError('Candidate requires library and library_sources together')
        if 'library' in record:
            if product != 'cbbg' or not str(record.get('library_release', '')).startswith('lib/v'):
                raise EvidenceError('Library dependency requires a CBBG candidate and library release')
            validate_release_identity(record['library_release'], manifest['commit'])
        if 'library_provenance' in record:
            if ('library' not in record
                    or not re.fullmatch(r'[0-9a-f]{40}', str(record.get('library_source_commit', '')))):
                raise EvidenceError('Library provenance requires its dependency artifact and source commit')
        if manifest['schema'] == 3:
            if (not isinstance(record.get('mapping'), dict)
                    or record.get('processing') != {'tool': 'proguard', 'version': '7.10.0'}):
                raise EvidenceError('Processed candidate requires a mapping and supported ProGuard version')
        elif 'mapping' in record or 'processing' in record:
            raise EvidenceError('Processed candidates require schema 3')
    catalog = load_catalog(checked_file(base, target['client_tests']['catalog']))
    selected = selected_target_specs(catalog, manifest['selected_targets'])
    validate_release_targets(manifest['release'], selected)
    if targets.keys() != selected.keys() or manifest['catalog_sha256'] != catalog_digest(catalog):
        raise EvidenceError('Candidate catalog or target selection differs')
    for identifier, specification in selected.items():
        owner = specification.get('artifactOf')
        if owner is not None:
            for kind in ('artifact', 'sources') + (('mapping',) if manifest['schema'] == 3 else ()):
                if targets[identifier][kind]['sha256'] != targets[owner][kind]['sha256']:
                    raise EvidenceError('Shared ' + kind + ' differs from owner: ' + identifier)
            for kind in ('utilities', 'utilities_sources', 'library', 'library_sources', 'library_release',
                         'library_provenance', 'library_source_commit'):
                if targets[identifier].get(kind) != targets[owner].get(kind):
                    raise EvidenceError('Shared ' + kind + ' differs from owner: ' + identifier)
    utilities_files = {}
    for record in targets.values():
        for kind in ('utilities', 'utilities_sources', 'library', 'library_sources', 'library_provenance'):
            if kind in record:
                reference = record[kind]
                checked_file(base, reference)
                if (reference['path'] in utilities_files
                        and utilities_files[reference['path']] != reference['sha256']):
                    raise EvidenceError('Conflicting candidate filename: ' + reference['path'])
                utilities_files[reference['path']] = reference['sha256']
    for kind in ('artifact', 'sources'):
        checked_file(base, target[kind])
    if 'source_inventory' in target:
        checked_file(base, target['source_inventory'])
    if manifest['schema'] == 3:
        checked_file(base, target['mapping'])
    return manifest, target, selected[target_id]
