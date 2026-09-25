#!/usr/bin/env python3
"""Validate local parity receipts against a prepared candidate and its exact files.

This checks receipt completeness and binding, not whether a client actually rendered
correct pixels. The local harness produces those assertions and retained evidence.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re

from targets import load_catalog


class EvidenceError(ValueError):
    pass


def read_json(path):
    def distinct(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise EvidenceError("Duplicate JSON key: " + key)
            result[key] = value
        return result
    return json.loads(Path(path).read_text(encoding="utf-8"), object_pairs_hook=distinct)


def digest(path):
    with Path(path).open("rb") as stream:
        checksum = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            checksum.update(block)
        return checksum.hexdigest()


def catalog_digest(catalog):
    """Bind catalog values independently of JSON whitespace and object-key order."""
    encoded = json.dumps(catalog, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def checked_file(directory, reference):
    """Require a regular file inside the evidence bundle with the expected hash."""
    relative = reference["path"]
    if not isinstance(relative, str) or not relative or Path(relative).is_absolute():
        raise EvidenceError("Evidence paths must be relative")
    base = Path(directory).resolve()
    path = (base / relative).resolve()
    if base not in path.parents or not path.is_file():
        raise EvidenceError("Missing or escaping evidence file: " + relative)
    if not re.fullmatch(r"[0-9a-f]{64}", reference["sha256"]):
        raise EvidenceError("Invalid SHA-256: " + relative)
    if digest(path) != reference["sha256"]:
        raise EvidenceError("Changed evidence file: " + relative)
    return path


def unique_by(items, key, description):
    result = {}
    for item in items:
        identifier = key(item)
        if identifier in result:
            raise EvidenceError("Duplicate " + description + ": " + str(identifier))
        result[identifier] = item
    return result


def validate_release_identity(release, commit):
    number = r"(?:0|[1-9][0-9]*)"
    prerelease = r"(?:-[A-Za-z][0-9A-Za-z-]*(?:\.[A-Za-z][0-9A-Za-z-]*)*\.[1-9][0-9]*)?"
    if not isinstance(release, str) or not re.fullmatch(
            "v" + number + r"\." + number + r"\." + number + prerelease, release):
        raise EvidenceError("Invalid release tag")
    if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise EvidenceError("Invalid source commit")


def selected_target_specs(catalog, selection):
    catalog_targets = unique_by(catalog['targets'], lambda t: t['id'], 'catalog target')
    if (not isinstance(selection, list) or not selection
            or any(not isinstance(name, str) for name in selection)
            or len(set(selection)) != len(selection)
            or not set(selection) <= catalog_targets.keys()):
        raise EvidenceError('Invalid explicit target selection')
    selected = set(selection)
    for target_id, specification in catalog_targets.items():
        owner = specification.get('artifactOf')
        if owner is not None:
            if target_id in selected and owner not in selected:
                raise EvidenceError('Selection omits a shared-artifact runtime: ' + target_id)
            if (owner in selected and target_id not in selected
                    and specification.get('implemented', True)):
                raise EvidenceError('Selection omits a shared-artifact runtime: ' + target_id)
    return {name: target for name, target in catalog_targets.items() if name in selected}


def verify(manifest_path, receipts_directory, catalog):
    manifest_path = Path(manifest_path)
    candidate = read_json(manifest_path)
    if type(candidate["schema"]) is not int or candidate["schema"] != 1:
        raise EvidenceError("Unsupported candidate schema")
    validate_release_identity(candidate["release"], candidate["commit"])
    # Old manifests retain their full-catalog meaning. Scope must be explicit.
    expected_targets = selected_target_specs(catalog, candidate.get(
        'selected_targets', [target['id'] for target in catalog['targets']]))
    targets = unique_by(candidate["targets"], lambda t: t["id"], "target")
    if targets.keys() != expected_targets.keys():
        scope = 'selection' if 'selected_targets' in candidate else 'catalog'
        raise EvidenceError('Candidate must cover the full target ' + scope)
    for target_id, specification in expected_targets.items():
        owner = specification.get("artifactOf")
        if owner is not None:
            for kind in ("artifact", "sources"):
                if targets[target_id][kind]["sha256"] != targets[owner][kind]["sha256"]:
                    raise EvidenceError("Shared " + kind + " differs from owner: " + target_id)
    required = set()
    bindings = {}
    for target_id, target in targets.items():
        specification = expected_targets[target_id]
        if not specification["implemented"]:
            raise EvidenceError("Unimplemented target: " + target_id)
        for kind in ("artifact", "sources", "harness", "dependency_lock", "scenario_contract"):
            checked_file(manifest_path.parent, target[kind])
        contract = read_json(checked_file(manifest_path.parent, target["scenario_contract"]))
        scenarios = contract["scenarios"]
        if (not isinstance(scenarios, list) or not scenarios
                or any(not isinstance(s, str) or not s for s in scenarios)
                or len(scenarios) != len(set(scenarios))):
            raise EvidenceError("Invalid scenario contract: " + target_id)
        lock = read_json(checked_file(manifest_path.parent, target["dependency_lock"]))
        locked_runtime = lock.get("runtime")
        if (not isinstance(locked_runtime, dict)
                or any(not isinstance(locked_runtime.get(field), str)
                       or not locked_runtime[field].strip()
                       for field in ("minecraft", "loader", "loader_version"))
                or type(locked_runtime.get("java")) is not int):
            raise EvidenceError("Missing locked runtime identity: " + target_id)
        for field in ("minecraft", "loader", "java"):
            if locked_runtime[field] != specification[field]:
                raise EvidenceError("Locked runtime differs from target: " + field + " " + target_id)
        # The dependency resolver must supply this inventory, including evidence for N/A.
        # Missing inventory is never interpreted as 'no optional mods'.
        inventory = specification.get("compatibilityProfiles")
        if (not isinstance(inventory, dict) or not inventory or "none" not in inventory
                or any(not isinstance(profile, str) or not profile for profile in inventory)
                or any(not isinstance(backends, list) or not backends
                       or any(not isinstance(backend, str) for backend in backends)
                       or len(backends) != len(set(backends))
                       or not set(backends) <= set(specification["backends"])
                       for backends in inventory.values())
                or set(inventory["none"]) != set(specification["backends"])):
            raise EvidenceError("Missing compatibility inventory: " + target_id)
        if (not isinstance(lock["profiles"], list)
                or any(not isinstance(profile, str) or not profile for profile in lock["profiles"])
                or len(lock["profiles"]) != len(inventory)
                or set(lock["profiles"]) != set(inventory)):
            raise EvidenceError("Dependency lock omits compatibility profiles: " + target_id)
        bindings[target_id] = (target, scenarios, locked_runtime)
        for profile, backends in inventory.items():
            for backend in backends:
                required.add((target_id, backend, profile))

    if candidate.get("catalog_sha256") != catalog_digest(catalog):
        raise EvidenceError("Candidate target catalog has changed")

    reports = []
    for path in sorted(Path(receipts_directory).glob("*.json")):
        reports.append((path, read_json(path)))
    actual = unique_by(reports, lambda pair: (pair[1]["target"], pair[1]["backend"],
                                            pair[1]["profile"]), "receipt")
    if actual.keys() != required:
        missing = required - actual.keys()
        unexpected = actual.keys() - required
        raise EvidenceError("Receipt coverage mismatch; missing=" + str(sorted(missing))
                            + "; unexpected=" + str(sorted(unexpected)))

    manifest_hash = digest(manifest_path)
    for cell, (path, report) in actual.items():
        target, scenarios, locked_runtime = bindings[cell[0]]
        if (type(report["schema"]) is not int or report["schema"] != 1
                or report["status"] != "passed"):
            raise EvidenceError("Receipt did not pass: " + str(cell))
        for name, expected in (("manifest_sha256", manifest_hash),
                               ("commit", candidate["commit"]), ("release", candidate["release"]),
                               ("actual_backend", cell[1])):
            if report[name] != expected:
                raise EvidenceError("Receipt binding mismatch: " + name + " " + str(cell))
        for kind in ("artifact", "sources", "harness", "dependency_lock", "scenario_contract"):
            if report[kind + "_sha256"] != target[kind]["sha256"]:
                raise EvidenceError("Receipt artifact mismatch: " + kind + " " + str(cell))
        runtime = report["runtime"]
        for field in ("minecraft", "loader", "loader_version"):
            if runtime.get(field) != locked_runtime[field]:
                raise EvidenceError("Wrong runtime identity: " + field + " " + str(cell))
        if (type(runtime["java"]) is not int
                or runtime["java"] != expected_targets[cell[0]]["java"]):
            raise EvidenceError("Wrong Java runtime: " + str(cell))
        for field in ("jvm", "os", "gpu", "driver"):
            if not isinstance(runtime[field], str) or not runtime[field].strip():
                raise EvidenceError("Missing runtime identity: " + field)
        results = unique_by(report["scenarios"], lambda s: s["id"], "scenario")
        if results.keys() != set(scenarios):
            raise EvidenceError("Scenario coverage mismatch: " + str(cell))
        if list(results) != scenarios:
            raise EvidenceError("Scenario order mismatch: " + str(cell))
        for scenario in results.values():
            if scenario["status"] != "passed" or scenario.get("timed_out") is not False:
                raise EvidenceError("Scenario did not pass: " + scenario["id"])
            evidence = scenario["evidence"]
            if not evidence:
                raise EvidenceError("Scenario has no retained evidence: " + scenario["id"])
            for reference in evidence:
                checked_file(path.parent, reference)
    return len(actual)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--receipts", type=Path, required=True)
    args = parser.parse_args()
    try:
        count = verify(args.manifest, args.receipts, load_catalog())
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.error(str(error))
    print("Verified " + str(count) + " local parity receipts")


if __name__ == "__main__":
    main()
