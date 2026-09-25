# Build and release commands

Run these commands from the repository root with Java 25. `targets.json`
defines target dependencies, build profiles, runtime Java versions and default
CI selection. Each profile uses its own Gradle process and can use its own
wrapper. The preserved 26.2 build is `fabric-upstream`.

| Task | Inputs and result |
| --- | --- |
| `build`, `check`, `dev`, `genSources` | Optional `-Ptarget=id` or `-Ptargets=id,id`; defaults to `ciTargets`. |
| `runClient` | Exactly one target; runs locally. |
| `checkCatalog` | Validates `targets.json`. |
| `targetMatrix` | Writes JSON with `-Poutput=path`; `-PrequireImplemented=true` requires completed targets. |
| `selectChecks` | `-Pbase=revision -Phead=revision -Poutput=path`; optional `-PgithubOutput=path`. |
| `checkPackages` | Builds and checks selected production and source JARs. |
| `candidateBuildOutputs` | Builds candidate inputs and records their hashes under the selected target's build directory. |
| `bundleCandidate` | `-PbuildOutputs=path -Pcontract=path -PruntimeLock=path -PdependencyLock=path -Prelease=vX.Y.Z -Poutput=new-directory`. Requires clean source matching the recorded build. |
| `verifyProvenance` | `-Pcandidate=path -Pbundle=provenance.jsonl -Prepo=owner/repository -Poutput=new-file`. Uses GitHub CLI attestation verification. |
| `verifyCandidate` | Provenance inputs plus `-Presults.TARGET=path` for each selected target; optional `-PsourceRoot=path`. Checks complete local game-test results. |
| `checkRelease` | `-Pcandidate=path -Presults.TARGET=path -Prepo=owner/repository -Prelease=tag -Pnotes=path -Poutput=new-file`; optional `-PsourceRoot=path`. Compares the GitHub draft with accepted local files. |
| `publishRelease` | Same inputs as `checkRelease`; publishes the draft. Run only after explicit release approval. |
| `preparePublication` | `-Prelease=tag -Prepo=owner/repository -PsourceRoot=clean-tag-checkout -Passets=new-directory -Poutput=new-file -PgithubOutput=path -Pservices=both`. Downloads immutable release assets and prepares service metadata. Supports historical `+mc` tags. |
| `checkHotfix` | `-Pbaselines=manifest,manifest -Ptargets=id,id -Poutput=new-file`; optional `-Phead=revision`. Checks changed targets against explicit prior release manifests. |

`candidateBuildOutputs` and `bundleCandidate` currently support the 26.3 Fabric
release path. A successful build does not establish release readiness. All
required local runtime results must match the candidate before publication.

The isolated `build-config/publishing` build uses Minotaur with the prepared
metadata and downloaded assets. It accepts `-PpublicationMetadata=path`,
`-Ptarget=id`, `-PsourceRoot=path`, and either `-Pcandidate=path` or
`-PlegacyAssets=directory`. `planModrinthUpload` checks retries without uploading;
`modrinth` uploads after approval. CurseForge uploads use the existing workflow
action and upload token. Check CurseForge manually before retrying an upload.
