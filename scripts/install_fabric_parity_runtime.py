#!/usr/bin/env python3
"""Install a catalog-pinned Fabric runtime for local packaged-artifact tests."""

import argparse
import hashlib
from importlib.metadata import version
import json
from pathlib import Path

from targets import load_catalog, select_targets
from fabric_runtime_lock import capture_runtime, fabric_command


def validate_profile(profile, target):
    loader = target["dependencies"]["loader"]
    identity = "fabric-loader-" + loader + "-" + target["minecraft"]
    if (profile.get("id") != identity
            or profile.get("inheritsFrom") != target["minecraft"]
            or profile.get("mainClass") != "net.fabricmc.loader.impl.launch.knot.KnotClient"):
        raise ValueError("Fabric profile identity mismatch")
    loaders = [lib["name"] for lib in profile.get("libraries", [])
               if lib["name"].startswith("net.fabricmc:fabric-loader:")]
    if loaders != ["net.fabricmc:fabric-loader:" + loader]:
        raise ValueError("Fabric profile loader mismatch")
    return identity


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--shared-runtime", type=Path)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    selected = select_targets(load_catalog(), args.target)
    if len(selected) != 1 or selected[0]["loader"] != "fabric":
        parser.error("Select exactly one Fabric target")
    target = selected[0]
    if version("minecraft-launcher-lib") != "8.0":
        raise ValueError("Use minecraft-launcher-lib==8.0 in an isolated environment")
    import requests
    from minecraft_launcher_lib.install import install_minecraft_version

    url = ("https://meta.fabricmc.net/v2/versions/loader/" + target["minecraft"]
           + "/" + target["dependencies"]["loader"] + "/profile/json")
    response = requests.get(url, timeout=30)
    response.raise_for_status()
    profile = response.json()
    identity = validate_profile(profile, target)
    runtime = args.runtime.resolve()
    receipt_path = runtime / "cbbg-install-receipt.json"
    if args.resume:
        previous = json.loads(receipt_path.read_text(encoding="utf-8"))
        if (previous.get("target") != target["id"] or previous.get("profile") != identity
                or previous.get("installed") is not False
                or previous.get("profileSha256") != hashlib.sha256(response.content).hexdigest()):
            raise ValueError("Only an incomplete matching installation may be resumed")
    else:
        runtime.mkdir(parents=True, exist_ok=False)
    receipt = {"target": target["id"], "profile": identity, "source": url,
               "profileSha256": hashlib.sha256(response.content).hexdigest(),
               "installed": False, "runtimeTested": False}
    try:
        if args.shared_runtime and not args.resume:
            shared = args.shared_runtime.resolve()
            for name in ("assets", "libraries"):
                source = shared / name
                if not source.is_dir():
                    raise ValueError("Shared runtime lacks " + name)
                (runtime / name).symlink_to(source, target_is_directory=True)
        profile_path = runtime / "versions" / identity / (identity + ".json")
        profile_path.parent.mkdir(parents=True, exist_ok=args.resume)
        profile_path.write_bytes(response.content)
        install_minecraft_version(identity, runtime,
                                  callback={"setStatus": lambda text: print(text, flush=True)})
        loader = target["dependencies"]["loader"]
        jar = runtime / "libraries/net/fabricmc/fabric-loader" / loader / ("fabric-loader-" + loader + ".jar")
        digest = hashlib.sha256(jar.read_bytes()).hexdigest()
        expected = target["dependencies"].get("loaderSha256")
        if expected is not None and digest != expected:
            raise ValueError("Fabric loader checksum mismatch")
        lock = capture_runtime(runtime, identity, fabric_command(runtime, identity))
        lock_bytes = (json.dumps(lock, indent=2) + '\n').encode('utf-8')
        (runtime / 'cbbg-runtime-lock.json').write_bytes(lock_bytes)
        receipt.update(installed=True, loaderSha256=digest,
                       runtimeLockSha256=hashlib.sha256(lock_bytes).hexdigest())
    finally:
        receipt_path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
