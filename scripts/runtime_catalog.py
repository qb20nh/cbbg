"""Read target records for local game tests; Gradle validates the build catalog."""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load_catalog(path=ROOT / 'targets.json'):
    catalog = json.loads(Path(path).read_text(encoding='utf-8'))
    if not isinstance(catalog, dict) or type(catalog.get('schema')) is not int or catalog['schema'] != 1:
        raise ValueError('Unsupported target catalog schema')
    records = catalog.get('targets')
    if not isinstance(records, list) or not records:
        raise ValueError('Target catalog must not be empty')
    identifiers = set()
    for record in records:
        identifier = record.get('id') if isinstance(record, dict) else None
        if not isinstance(identifier, str) or not identifier or identifier in identifiers:
            raise ValueError('Invalid or duplicate runtime target')
        identifiers.add(identifier)
    return catalog


def select_targets(catalog, selection=None):
    records = catalog['targets']
    if selection is None:
        return records
    requested = selection.split(',')
    if any(not item for item in requested) or len(requested) != len(set(requested)):
        raise ValueError('Selection must contain unique, nonempty target IDs')
    unknown = set(requested) - {record['id'] for record in records}
    if unknown:
        raise ValueError('Unknown targets: ' + ', '.join(sorted(unknown)))
    return [record for record in records if record['id'] in requested]
