# Local Fabric runtime locks

These locks record the ordered launch classpath, inherited version profiles and
extracted native-library files for a specific installed runtime. Paths are
relative to its runtime directory, including shared-library symlinks. They do
not include credentials, mod jars, assets, Java binaries or GPU/driver state;
those need separate acceptance evidence.

Using the isolated environment with `minecraft-launcher-lib==8.0`, verify before
launching a packaged client:

```
python scripts/fabric_runtime_lock.py --runtime /path/to/runtime \
  --profile fabric-loader-0.19.5-26.3 \
  --lock runtime-locks/26.3-fabric-linux-x86_64.json --verify
```

The installer writes `cbbg-runtime-lock.json` and records its checksum in its
installation receipt. To record a new local baseline explicitly, omit `--verify`
and supply a new lock path; existing files are never overwritten by that command.
Review a dependency change before replacing a checked-in baseline.

The initial 26.3 Linux lock was captured after the existing compatibility runs.
It establishes a baseline for subsequent launches, not proof of the historical
runtime bytes used by those earlier runs. A matching lock is not a test pass.

`26.3-fabric-mods.json` separately locks Fabric API and the optional mods selected
by the target catalog. Its entries cite the publisher's version metadata used
to verify the downloaded bytes. `verify_dependencies` checks catalog pins,
selected jars and required transitive dependencies (Iris/Sodium and
RenderScale/Cloth Config). Candidate, driver and gametest API hashes still need
separate receipt binding. A mod dependency lock does not establish compatibility.
