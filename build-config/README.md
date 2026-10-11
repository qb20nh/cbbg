# Build and release commands

To test major Dependabot updates together, use the
[draft batch workflow](../.github/DEPENDABOT_BATCH.md).

Run these commands from the repository root with Java 25. `targets.json`
defines target dependencies, build profiles, runtime Java versions and default
CI selection. Each profile uses its own Gradle process and can use its own
wrapper. The preserved 26.2 build is `fabric-upstream`.

Each profile declares `sharedProjects` and applies `shared-code.gradle` to use
that list for compilation and binary/source packaging. `core` contains common
configuration and `core:legacy` contains legacy commands and language support.
`libraries/utilities` owns reusable dithering, noise and FFT code;
`libraries/fabric` owns GPU implementations and packages the standalone CBBG Lib
mod. CBBG embeds that library JAR and retains its own client entrypoint and
settings. Add only the modules a target uses. Split a module when a target needs only part of it.

Ordinary CI selects target builds from changed files. Fabric profiles and the
selector use `FabricSources` for source directories, file filters and resource
copies. Catalog edits select changed artifacts, including their runtime aliases.
Shared core or unknown inputs select every CI target. The selection report and
logs list the selected and skipped builds. Release acceptance keeps its full checks.

Target jobs start after selection while tooling tests run separately. The `plan`
check reports their combined result; known documentation-only changes skip tooling.
Dispatched builds inherit the root's build-cache choice. Use `--no-build-cache`
to disable reuse. `ciCheck dev` checks and builds each artifact in one child process.

| Task | Inputs and result |
| --- | --- |
| `build`, `check`, `dev`, `genSources` | Optional `-Ptarget=id` or `-Ptargets=id,id`; defaults to `ciTargets`. |
| `runClient` | Exactly one target; runs locally. |
| `checkCatalog` | Validates `targets.json`. |
| `targetMatrix` | Writes JSON with `-Poutput=path`; `-PrequireImplemented=true` requires completed targets. |
| `codeqlScan` | `-PcodeqlExecutable=path`; analyzes CI and implemented artifact owners. Optional `-PcodeqlLegacyTarget=id` retains a historical scan. |
| `selectChecks` | `-Pbase=revision -Phead=revision -Poutput=path`; optional `-PgithubOutput=path`. |
| `checkPackages` | Builds and checks selected production and source JARs. |
| `optimizeReleaseJar` | Shrinks, optimizes and obfuscates the selected targets' release JARs, with per-target mappings. |
| `retrace` | `-Pcandidate=path -Ptarget=id -Pcrash=log-file -Poutput=new-file`; decodes a crash using that release's verified mapping. |
| `candidateBuildOutputs` | CBBG: root task with `-Ptarget=id`, writes `build/targets/<owner>/candidate-build-outputs.json`. CBBG Lib: `./gradlew -p libraries/fabric -Ptarget=id -Plibrary_version=1.0.0 candidateBuildOutputs`, writes root `build/libraries/<owner>/candidate-build-outputs.json`. |
| `releaseIdentity` | `-Prelease=tag -Poutput=path`; writes product, version, title and changelog path. Optional `-PrequireProduct=cbbg` or `lib`. |
| `releaseNotes` | `-Prelease=tag -Ptarget=id` or `-Ptargets=id,id`, plus `-Poutput=path`; selects the product's changelog entry. |
| `bundleCandidate` | `-PbuildOutputs=path -Pcontract=path -PruntimeLock=path -PdependencyLock=path -Prelease=tag -Poutput=new-directory`. Requires clean source matching the recorded build. |
| `verifyLibraryDependencies` | CBBG only: `-Pcandidate=path -Prepo=owner/repository -Poutput=new-file`; optional `-PsourceRoot=path`. Verifies a library draft or immutable release and binds its provenance before candidate attestation. The report must be outside the candidate directory. |
| `verifyProvenance` | `-Pcandidate=path -Pbundle=provenance.jsonl -Prepo=owner/repository -Poutput=new-file`. Uses GitHub CLI attestation verification. |
| `runCandidateAcceptance` | Local only: `-Pcandidate=path -Poutput=directory -PacceptancePython=executable -PacceptanceJava21=executable -PacceptanceJava25=executable`; requires Weston. Optional target selection, `-PacceptanceWorkers=N`, and `-PacceptanceReuse=directory`. Runs the product's packaged acceptance contracts. |
| `verifyCandidate` | Provenance inputs plus `-Presults.TARGET=path` for each selected target; optional `-PsourceRoot=path`. Checks complete local game-test results. |
| `checkRelease` | `-Pcandidate=path -Presults.TARGET=path -Prepo=owner/repository -Prelease=tag -Pnotes=path -Poutput=new-file`; optional `-PsourceRoot=path`. Compares the GitHub draft with accepted local files. |
| `publishRelease` | Same inputs as `checkRelease`; publishes the draft. Run only after explicit release approval. |
| `preparePublication` | `-Prelease=tag -Prepo=owner/repository -PsourceRoot=clean-tag-checkout -Passets=new-directory -Poutput=new-file -PgithubOutput=path -Pservices=both`. CBBG only; downloads immutable release assets and prepares service metadata. `-PdryRun=true` also permits draft preflight without uploading. Supports historical `+mc` tags. |
| `checkHotfix` | `-Pbaselines=manifest,manifest -Ptargets=id,id -Poutput=new-file`; optional `-Phead=revision`. Checks changed targets against explicit prior release manifests. |

CBBG versions come from root `gradle.properties` (`mod_version=1.5.0`); CBBG Lib
versions come from `libraries/utilities/gradle.properties`
(`library_version=1.0.0`). Release tags are `v<version>` and `lib/v<version>`,
respectively, with optional `+mc<minecraft>-<loader>` target scope. The shared
parser selects `CHANGELOG.md` or `libraries/CHANGELOG.md`.

Candidate builds use artifact owners from `targets.json`; runtime aliases share
an owner JAR. CBBG Lib supports the Fabric/Quilt runtime family through
`libraries/fabric`. Main contracts are `runtime-locks/<id>-scenarios.json`;
library contracts are `runtime-locks/lib/<id>-scenarios.json`. Dependency and
runtime locks remain under `runtime-locks/`. A successful build does not establish
release readiness: each product requires its own complete packaged runtime
results. CBBG candidates verify the referenced library draft before attestation;
CBBG finalization requires that library release to be published and immutable.

Main GitHub releases contain CBBG mod and sources JARs, library dependency
provenance, release provenance and checksums. Library GitHub releases contain
CBBG Lib mod and sources JARs plus the utility binary and sources once per
release, with provenance and checksums. Only CBBG uses Modrinth/CurseForge; those
uploads attach the exact library and utility binaries and sources from its
verified candidate.

CodeQL uses a separate database for each artifact owner and shares one Java CI
job. Patch and loader aliases of the same artifact share its scan. Builds run
sequentially with clean outputs; completed databases are removed before the next
analysis. The plan and SARIF reports are in `.gradle/codeql-results/`, outside
the historical 26.2 profile's cleanup directory. The Java upload runs only after
every selected analysis succeeds. Java, Python and Actions analyses run on PRs,
pushes to `main` and the weekly schedule.

When adopting this workflow, replace required target-specific CodeQL checks with
`CodeQL Advanced / Analyze (java-kotlin)`. GitHub may report a configuration
transition warning on the migration PR because the Java matrix was removed.

Release JARs use ProGuard to shrink, optimize and obfuscate each product.
Development JARs retain ordinary names. Each processed release includes its mapping and
CycloneDX SBOM in the sources JAR under `META-INF/cbbg/`. Private candidates
also retain the mapping referenced by their manifest. Use that candidate
manifest and matching mapping when running `retrace`. Use the release
and target from the crash report; mappings belong to a specific build.

The isolated `build-config/publishing` build uses Minotaur with the prepared
metadata and downloaded assets. It accepts `-PpublicationMetadata=path`,
`-Ptarget=id`, `-PsourceRoot=path`, and either `-Pcandidate=path` or
`-PlegacyAssets=directory`. `planModrinthUpload` checks retries without uploading;
`modrinth` uploads after approval. CurseForge uploads use the existing workflow
action and upload token. Check CurseForge manually before retrying an upload.
