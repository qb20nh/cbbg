# Local Fabric runtime locks

These files record the launch classpath in order, inherited version profiles and
extracted native libraries for an installed runtime. Paths, including shared-library
symlinks, are relative to the runtime directory. Record mod jars, assets, Java
binaries and GPU/driver details separately; keep credentials out of these files.

Using the isolated environment with `minecraft-launcher-lib==8.0`, verify before
launching a packaged client:

```
python scripts/fabric_runtime_lock.py --runtime /path/to/runtime \
  --profile fabric-loader-0.19.5-26.3 \
  --lock runtime-locks/26.3-fabric-linux-x86_64.json --verify
```

The installer writes `cbbg-runtime-lock.json` and records its checksum in the
installation receipt. To record another runtime, omit `--verify` and supply a
new lock path. The command requires a new file. Review dependency changes before
replacing a committed lock.

The initial 26.3 Linux lock was captured after the compatibility runs and applies
to subsequent launches. Earlier runs lack this runtime record. Compatibility
still requires game tests, even when the runtime matches the lock.

`26.3-fabric-mods.json` records Fabric API and the optional mods selected by the
target catalog. Entries cite the publisher metadata used to verify downloads.
`verify_dependencies` checks catalog versions, selected jars and dependencies
such as Sodium for Iris and Cloth Config for RenderScale. The `gametestApi`
entry records the test API module for the catalog's Fabric API version. The
launcher verifies that jar before creating the game directory and after copying
it. Receipts must also record candidate and driver hashes. Older receipts keep
their original lock digest and describe the checks made at the time.

For the dedicated `irisRestartDriverJar`, use `fabric_parity_runtime.py` with
`--backend opengl --restart-phase prepare`, then repeat the same launch arguments
and game directory with `--restart-phase verify`. The first invocation requires a
new directory. The second preserves its settings and installed jars, verifies the
successful prepare receipt and its evidence, and rejects changed runtime/source
identities, artifacts, CBBG/Iris settings or fixture shader-pack files. Each JVM
writes separate phase-prefixed logs, evidence and receipts; a verification attempt
cannot overwrite an earlier attempt. `--restart-phase control` creates a separate
fresh disabled-effect control. These options require the dedicated restart driver.
Graphics-context recording preserves shader state and startup preferences.
