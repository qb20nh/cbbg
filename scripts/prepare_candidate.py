#!/usr/bin/env python3
"""Hash an explicit local bundle inventory into the draft parity manifest format.

No build, download, runtime acceptance or publication is performed here.
"""
import argparse
import json
from pathlib import Path

from parity_evidence import EvidenceError, catalog_digest, digest, read_json, unique_by, validate_release_identity, selected_target_specs
from targets import load_catalog

FILES = ("artifact", "sources", "harness", "dependency_lock", "scenario_contract")
CLIENT_FILES = ('catalog', 'contract', 'ordinary_metadata', 'runtime_lock', 'dependency_lock')


def file_reference(base, relative):
    if not isinstance(relative, str) or not relative or Path(relative).is_absolute():
        raise EvidenceError('Candidate paths must be relative')
    path = (base / relative).resolve()
    if base not in path.parents or not path.is_file():
        raise EvidenceError('Missing or escaping candidate file: ' + relative)
    return {'path': path.relative_to(base).as_posix(), 'sha256': digest(path)}


def prepare(directory, inventory, release, commit, catalog, selection=None):
    validate_release_identity(release, commit)
    base = Path(directory).resolve()
    supplied = unique_by(inventory, lambda entry: entry["id"], "target")
    expected = selected_target_specs(catalog, selection if selection is not None else
                                     [target['id'] for target in catalog['targets']])
    if supplied.keys() != expected.keys():
        raise EvidenceError("Inventory must cover the full target selection")
    client_bundles = any('client_tests' in item for item in supplied.values())
    if client_bundles and any('client_tests' not in item for item in supplied.values()):
        raise EvidenceError('Every selected target needs a client test bundle')
    targets = []
    for identifier in expected:
        record = {"id": identifier}
        for kind in ('artifact', 'sources') if client_bundles else FILES:
            record[kind] = file_reference(base, supplied[identifier][kind])
        if client_bundles:
            if 'source_inventory' not in supplied[identifier]:
                raise EvidenceError('Missing candidate source inventory: ' + identifier)
            record['source_inventory'] = file_reference(base, supplied[identifier]['source_inventory'])
            tests = supplied[identifier]['client_tests']
            record['client_tests'] = {kind: file_reference(base, tests[kind]) for kind in CLIENT_FILES}
            if not isinstance(tests['drivers'], dict) or not tests['drivers']:
                raise EvidenceError('Missing client test drivers')
            record['client_tests']['drivers'] = {
                suite: file_reference(base, path) for suite, path in sorted(tests['drivers'].items())}
        targets.append(record)
    indexed = {record["id"]: record for record in targets}
    for identifier, specification in expected.items():
        owner = specification.get("artifactOf")
        if owner is not None:
            for kind in ("artifact", "sources"):
                if indexed[identifier][kind]["sha256"] != indexed[owner][kind]["sha256"]:
                    raise EvidenceError("Shared " + kind + " differs from owner: " + identifier)
    manifest = {"schema": 2 if client_bundles else 1, "release": release, "commit": commit,
                "catalog_sha256": catalog_digest(catalog), "targets": targets}
    if selection is not None or client_bundles:
        manifest['selected_targets'] = list(expected)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--release", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--targets", help="Comma-separated explicit target IDs, including shared-jar runtimes")
    args = parser.parse_args()
    try:
        manifest = prepare(args.output.parent, read_json(args.inventory), args.release,
                           args.commit, load_catalog(),
                           args.targets.split(',') if args.targets is not None else None)
        # Never replace an earlier candidate after receipts have bound its hash.
        with args.output.open("x", encoding="utf-8") as stream:
            stream.write(json.dumps(manifest, indent=2) + "\n")
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.error(str(error))
    print("Prepared candidate manifest: " + str(args.output))


if __name__ == "__main__":
    main()
