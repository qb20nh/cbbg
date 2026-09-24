#!/usr/bin/env python3
"""Run selected adapters in separate Gradle processes, without loading their plugins together."""

import argparse
import subprocess

from targets import ROOT, load_catalog, select_artifacts, select_targets


def commands(catalog, task, selection, *, offline=False, properties=(), root=ROOT):
    if task not in {"build", "check", "runClient", "genSources", "dev"}:
        raise ValueError("Unsupported dispatch task: " + task)
    selected = select_targets(catalog, selection, require_implemented=task == "build")
    if task in {"build", "check", "genSources", "dev"}:
        selected = select_artifacts(catalog, selection, require_implemented=task == "build")
    if task == "runClient" and len(selected) != 1:
        raise ValueError("runClient requires exactly one target")
    for prop in properties:
        if prop.partition("=")[0] not in {"compat", "backend", "testJava"} or "=" not in prop:
            raise ValueError("Unsupported forwarded property: " + prop)
    result = []
    for target in selected:
        profile = target.get("buildProfile")
        if profile is None:
            raise ValueError("No build profile configured for " + target["id"])
        if profile == "fabric-upstream":
            if target["id"] != "26.2-fabric":
                raise ValueError("Upstream build belongs to 26.2-fabric")
            directory = root
        else:
            directory = root / "builds" / profile
            if directory.parent != root / "builds" or not (directory / "build.gradle").is_file():
                raise ValueError("Invalid build profile: " + profile)
        wrapper = directory / "gradlew"
        if not wrapper.is_file():
            wrapper = root / "gradlew"
        command = [str(wrapper), "-p", str(directory), task, "--no-daemon", "--no-watch-fs",
                   "--project-cache-dir", str(root / ".gradle" / "dispatch" / target["id"])]
        if profile != "fabric-upstream":
            command.append("-Ptarget=" + target["id"])
        if offline:
            command.append("--offline")
        command.extend("-P" + prop for prop in properties)
        result.append(command)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("task", choices=["build", "check", "runClient", "genSources", "dev"])
    parser.add_argument("--targets", required=True)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--property", action="append", default=[])
    args = parser.parse_args()
    try:
        planned = commands(load_catalog(), args.task, args.targets,
                           offline=args.offline, properties=args.property)
    except ValueError as error:
        parser.error(str(error))
    for command in planned:
        result = subprocess.run(command, cwd=ROOT)
        if result.returncode:
            return result.returncode
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
