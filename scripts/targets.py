#!/usr/bin/env python3
"""Read the distribution matrix without importing a Minecraft build plugin."""

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load_catalog(path=ROOT / "targets.json"):
    catalog = json.loads(Path(path).read_text(encoding="utf-8"))
    if catalog.get("schema") != 1:
        raise ValueError("Unsupported target catalog schema")
    targets = catalog.get("targets")
    if not isinstance(targets, list) or not targets:
        raise ValueError("Target catalog must not be empty")
    ids = set()
    for target in targets:
        target_id = target["id"]
        if target_id in ids:
            raise ValueError("Duplicate target: " + target_id)
        ids.add(target_id)
        if target["loader"] not in {"fabric", "legacy-fabric", "forge", "neoforge", "quilt"}:
            raise ValueError("Unknown loader: " + target_id)
        if target_id != target["minecraft"] + "-" + target["loader"]:
            raise ValueError("Inconsistent target ID: " + target_id)
        if type(target["java"]) is not int or target["java"] not in {8, 17, 21, 25}:
            raise ValueError("Unsupported Java version: " + target_id)
        if type(target.get("implemented")) is not bool:
            raise ValueError("Missing implementation status: " + target_id)
        if target["renderer"] == "classic-gl" and target.get("projectionApi") not in {"joml", "mojang-math"}:
            raise ValueError("Missing or unsupported classic projection API: " + target_id)
        if target.get("buildProfile") in {"fabric-classic", "forge-classic"}:
            groups = target.get("sourceGroups")
            if not isinstance(groups, list) or not groups or any(not isinstance(group, str) or not group for group in groups):
                raise ValueError("Classic build requires source groups: " + target_id)
        backends = target["backends"]
        if (not isinstance(backends, list) or not backends
                or len(backends) != len(set(backends))
                or not set(backends) <= {"opengl", "vulkan"}):
            raise ValueError("Invalid backends: " + target_id)
        if "vulkan" in backends and target["renderer"] not in {"blaze-gpu-format", "renderpearl"}:
            raise ValueError("Renderer does not support Vulkan: " + target_id)
        profiles = target.get("compatibilityProfiles")
        if profiles is not None:
            if not isinstance(profiles, dict) or "none" not in profiles:
                raise ValueError("Compatibility profiles require a base fixture: " + target_id)
            for name, supported in profiles.items():
                if (not name or not isinstance(supported, list) or not supported
                        or any(not isinstance(backend, str) for backend in supported)
                        or len(supported) != len(set(supported))
                        or not set(supported) <= set(backends)):
                    raise ValueError("Invalid compatibility backends: " + target_id)
            if set(profiles["none"]) != set(backends):
                raise ValueError("Base fixture must cover every backend: " + target_id)
    by_id = {target["id"]: target for target in targets}
    for target in targets:
        artifact_of = target.get("artifactOf")
        if artifact_of is None:
            continue
        owner = by_id.get(artifact_of) if isinstance(artifact_of, str) else None
        if (owner is None or owner is target or owner.get("artifactOf") is not None
                or target["loader"] != "quilt" or owner["loader"] != "fabric"
                or owner["minecraft"] != target["minecraft"]
                or owner["java"] > target["java"]
                or owner["renderer"] != target["renderer"]
                or owner.get("projectionApi") != target.get("projectionApi")
                or set(owner["backends"]) != set(target["backends"])):
            raise ValueError("Invalid shared artifact reference: " + target["id"])
        if target["implemented"] and not owner["implemented"]:
            raise ValueError("Shared artifact owner is not implemented: " + target["id"])
    return catalog


def select_targets(catalog, selection=None, require_implemented=False):
    targets = catalog["targets"]
    if selection is not None:
        requested = selection.split(",")
        if any(not item for item in requested) or len(requested) != len(set(requested)):
            raise ValueError("Selection must contain unique, nonempty target IDs")
        unknown = set(requested) - {target["id"] for target in targets}
        if unknown:
            raise ValueError("Unknown targets: " + ", ".join(sorted(unknown)))
        targets = [target for target in targets if target["id"] in requested]
    if require_implemented:
        pending = [target["id"] for target in targets if not target["implemented"]]
        if pending:
            raise ValueError("Targets not implemented: " + ", ".join(pending))
    return targets


def select_build_profile(catalog, profile, require_implemented=False):
    selected = [target["id"] for target in catalog["targets"]
                if target.get("buildProfile") == profile]
    if not selected:
        raise ValueError("Unknown or empty build profile: " + profile)
    return select_targets(catalog, ",".join(selected), require_implemented)


def select_artifacts(catalog, selection=None, require_implemented=False):
    """Resolve build owners without changing the runtime target selection API."""
    selected = select_targets(catalog, selection, require_implemented)
    by_id = {target["id"]: target for target in catalog["targets"]}
    owners = []
    seen = set()
    for target in selected:
        owner = by_id[target.get("artifactOf", target["id"])]
        if require_implemented and not owner["implemented"]:
            raise ValueError("Artifact owner not implemented: " + owner["id"])
        if owner["id"] not in seen:
            owners.append(owner)
            seen.add(owner["id"])
    return owners


def build_matrix(catalog, selection=None, require_implemented=False):
    runtimes = select_targets(catalog, selection, require_implemented)
    owners = select_artifacts(catalog, selection, require_implemented)
    rows = []
    for owner in owners:
        if not owner.get('buildProfile'):
            raise ValueError('No build profile configured for ' + owner['id'])
        row = {field: owner[field] for field in
               ('id', 'minecraft', 'loader', 'java', 'renderer', 'buildProfile')}
        row['runtimeTargets'] = [target['id'] for target in runtimes
                                 if target.get('artifactOf', target['id']) == owner['id']]
        rows.append(row)
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--targets", help="Exact comma-separated target IDs")
    selection.add_argument("--build-profile", help="Configured targets in one isolated build profile")
    parser.add_argument("--require-implemented", action="store_true")
    parser.add_argument('--artifacts', action='store_true',
                        help='Emit one build job per artifact, retaining selected runtime IDs')
    args = parser.parse_args()
    try:
        catalog = load_catalog()
        selected = (select_build_profile(catalog, args.build_profile, args.require_implemented)
                    if args.build_profile is not None
                    else select_targets(catalog, args.targets, args.require_implemented))
        if args.artifacts:
            selected = build_matrix(catalog, ','.join(target['id'] for target in selected),
                                    args.require_implemented)
    except (ValueError, KeyError, TypeError) as error:
        parser.error(str(error))
    print(json.dumps({"include": selected}, separators=(",", ":")))


if __name__ == "__main__":
    main()
