# Test major dependency updates together

Run **Dependabot major batch** from the Actions tab, selecting `main`. Leave
`pr_numbers` blank to combine all eligible major updates, or enter numbers such
as `37, 53` to create a fixed diagnostic subset. Only one managed draft can
exist at a time. Mark the previous batch ready or close it before creating another.

The utility snapshots `main` and the exact heads of open, same-repository,
non-draft Dependabot PRs targeting `main`. It classifies major versions using
Dependabot's `updated-dependencies` commit metadata and requires a Dependabot
head commit. SHA-only, unknown, malformed, and manually edited heads are
excluded. An explicitly requested ineligible PR fails the run. Failing source
checks are allowed: the batch exists to test their combined behavior.

The utility creates a draft under `automation/dependabot-major-batch/` and lists
the base, source SHAs, and dependency metadata in its body. It uses server-side
branch merges and never executes source PR code. Conflicts fail the whole
assembly; source PRs stay open. It never merges into `main` or publishes a release.

While an all-major batch is draft, newly opened, reopened, or ready-for-review
Dependabot PRs trigger reconciliation of eligible PRs created after that batch.
New sources are staged together before a fast-forward promotion. A conflict
leaves the draft unchanged. Existing source SHAs stay frozen, including when
Dependabot updates their original PRs. Subset batches stay fixed. Ready, closed,
and merged batches receive no automatic additions.

## Run and review checks

Repository **Settings → Actions → General → Workflow permissions** must allow
GitHub Actions to create pull requests. GitHub combines PR creation and review
approval in one repository setting; this utility never submits approving reviews.
Keep the default token permission at read; only the utility job requests Contents
and Pull requests write access.

`GITHUB_TOKEN` can assemble Gradle-only updates, but GitHub rejects branch merges
that change `.github/workflows/` without `workflows` permission. The first
[all-major live run](https://github.com/qb20nh/cbbg/actions/runs/36329554317)
confirmed this restriction and removed its temporary branch without creating a PR.
Configure the optional GitHub App below to include GitHub Actions updates.
Without it, an all-major batch containing those updates fails explicitly. A
Gradle-only diagnostic subset does not verify the full batch.

## Configure the branch GitHub App

1. [Register a private GitHub App](https://github.com/settings/apps/new). Use the
   repository URL for its homepage, disable the webhook, and allow installation
   only on your account.
2. Grant repository **Contents: Read and write** and **Workflows: Read and write**.
   Metadata read access is automatic. Do not grant Pull requests or other write
   permissions; this App does not create or approve PRs.
3. Install the App with **Only select repositories**, selecting `cbbg`.
4. In the repository's **Settings → Secrets and variables → Actions**, add a
   variable named `DEPENDABOT_BATCH_APP_CLIENT_ID` with the App's Client ID.
5. Generate an App private key and put the entire PEM file into a repository
   secret named `DEPENDABOT_BATCH_APP_PRIVATE_KEY`.

The workflow uses GitHub's pinned `actions/create-github-app-token` release to
request a token limited to this repository and those two permissions. The action
revokes the token when the job finishes. Only branch mutations and server-side
merges use it; PR reads, creation, and body updates still use `GITHUB_TOKEN`, so
managed PRs keep the `github-actions[bot]` author. No source PR code runs in the
job holding either token. If the Client ID is unset, Gradle-only operation falls
back to `GITHUB_TOKEN`; a configured App with a missing key or installation fails
rather than silently falling back.

## Approve and review PR checks

PRs created or updated with `GITHUB_TOKEN` require a maintainer to approve their
workflows, as described in [GitHub's trigger documentation](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow#triggering-a-workflow-from-a-workflow).
The [Gradle-only live diagnostic](https://github.com/qb20nh/cbbg/pull/59) confirmed
that Java CI, CodeQL, and OSV runs are created in an approval-required state.
Open the batch and choose **Approve workflows to run** whenever GitHub displays
the approval banner. PR creation uses `GITHUB_TOKEN`; branch updates using the
App token can trigger CI without that prompt. Confirm fresh checks for every new
head: Java CI runs formatting, build/release tooling
tests, core tests on Java 8/17/21/25, and every catalog `ciTargets` build and package
check, along with the existing CodeQL and OSV workflows. An assembly run passing
does not mean the PR checks passed. Review the combined diff and check results
for the current head before merging manually.

Mark the draft ready only after the utility is idle. GitHub does not provide an
atomic operation combining draft-state checks and branch updates, so changing
draft state during the final API write can race an addition. Manual branch edits
or managed-metadata changes stop subsequent automatic updates.

The run uploads `snapshot.json` and `summary.md`, including exclusions and cleanup
errors. If a ref promotion succeeds but updating the PR body fails, later runs
stop on the mismatched managed head. Inspect the actual branch and the artifact
before restoring the generated metadata from its `snapshot`. Do not overwrite
human edits. A response lost during a write can retain a temporary branch;
inspect the reported ref and any attached PR before deleting it. Do not reuse
an existing utility branch to retry; run again to get a new run identity.

## Release acceptance

Combined PR CI covers unit tests and packaging; it does not launch Minecraft.
Keep the existing release path: build an attested draft candidate, run the local
game and graphics acceptance tests against that exact candidate, then validate
their hash-bound results before `checkRelease`/`publishRelease`. Passing batch
CI does not replace those release gates or authorize publication.
