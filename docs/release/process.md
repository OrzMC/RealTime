# Release Process

RealTime uses SemVer tags prefixed with `v`. The release workflow accepts stable tags such as `v0.1.0` and prerelease tags such as `v0.2.0-rc.1`. Build metadata suffixes are intentionally not accepted.

## Prepare a release

1. Update `CHANGELOG.md` with a non-empty section whose heading exactly matches `## [MAJOR.MINOR.PATCH]` or the full prerelease version.
   The section is rendered as the GitHub Release notes, so it must contain a short **升级须知 / upgrade notes** subsection
   whenever a user has to do something manually (migration, behaviour change, a fixed bug that leaves stale state behind).
   Commit lists alone do not tell an operator what to do.
2. Review compatibility, configuration/data migrations, Paper/Folia runtime evidence, and the shaded JAR contents.
3. Merge the release-preparation changes to `main`.
4. Create and push an annotated tag from the verified commit, for example:

   ```bash
   git tag -a v0.1.0 -m "RealTime v0.1.0"
   git push origin v0.1.0
   ```

The tag workflow validates the tag and changelog, builds with `-PprojectVersion` set from the tag, runs the project verification suite, inspects the shaded JAR, and creates a SHA-256 checksum. The artifact and extracted changelog section are retained as workflow artifacts.

## GitHub Release protection

The workflow never runs on `pull_request` events. The build job has `contents: read` and receives no publishing credentials. The GitHub Release job is disabled unless the repository variable `RELEASE_PUBLISH_ENABLED` is explicitly set to `true`.

Before enabling it, a repository administrator must create a GitHub Actions environment named `release` and configure:

- Required reviewers (at least one independent human approval).
- Deployment branches/tags restricted to the release tag pattern `v*`.
- No secrets at repository-wide scope when an environment-scoped secret is sufficient.

Only the gated release job receives `contents: write`. The job waits for the environment's required approval before creating a GitHub Release.

## Hangar and Modrinth

Hangar uses the official PaperMC Gradle publish plugin; Modrinth is called through its v2 API. Both steps run in the
protected `release` job, use the same shaded JAR and the same changelog section as the GitHub Release, and are skipped
(not failed) while their project id or token is missing.

- Hangar: `HANGAR_PROJECT_ID` (repository variable, the project **slug**) + `HANGAR_API_TOKEN` (environment secret,
  `create_version` scope). Stable tags only.
- Modrinth: `MODRINTH_PROJECT_ID` (repository variable) + `MODRINTH_TOKEN` (environment secret, `CREATE_VERSION` scope).
  Loaders are `paper` + `folia`, game version `26.3`.

Setup steps, the payload, the dry-run channel and the troubleshooting table live in
[`distribution.md`](distribution.md). Keep distribution credentials inside the protected job only — never in
pull-request workflows, repository files, Gradle properties, or logs.

## First public release readiness

`v0.1.0` was released on 2026-11-24 (see [acceptance report](../operations/acceptance-report-0.1.0.md) §10). The
`release` environment now exists with a required reviewer and a `v*` tag policy, and `RELEASE_PUBLISH_ENABLED`
is `true`. Hangar and Modrinth publishing are intentionally left unconfigured until the release has proven stable
in production; the workflow skips both steps while their project IDs are unset.

Checklist used for the first release (keep for future releases):

1. Confirm the exact `main` commit contains PR11 and all Paper/Folia runtime checks are green.
2. Review `CHANGELOG.md` section `## [0.1.0]` and the generated shaded JAR contents.
3. Create the GitHub Actions `release` environment. Require at least one independent reviewer and restrict deployments to `v*` tags.
4. Configure `HANGAR_PROJECT_ID` and `MODRINTH_PROJECT_ID` as environment variables, and `HANGAR_API_TOKEN` / `MODRINTH_TOKEN` as environment secrets with project-scoped version-create permissions (see [`distribution.md`](distribution.md)).
5. Keep `RELEASE_PUBLISH_ENABLED` unset or `false` until the environment protection and external project identities have been reviewed. Enabling it allows the protected job to create a GitHub Release and publish to whichever external platforms have both project IDs and tokens configured.
6. Only after explicit release approval, create and push the annotated `v0.1.0` tag.

If publishing is not enabled, a tag run only validates/builds the artifact and uploads the JAR, checksum, and release notes as a workflow artifact. It does not create a GitHub Release or publish to Hangar/Modrinth.

## After publishing

Once the protected job finishes, audit the platform pages against
[`../operations/platform-listings.md`](../operations/platform-listings.md):

```bash
MODRINTH_TOKEN="$(cat ~/modrinth.token)" scripts/platform-audit.sh
```

It compares name, category, licence, description lengths, links, platforms/game versions and loaders, and checks every
Hangar version's channel, `26.3` declaration and sha256 against the released artifact. Hangar needs no credentials; the
Modrinth half needs a token with `READ_PROJECT` and reports "cannot read" instead of passing silently when it cannot.

## Release history

| Version | Date | Tag source | Notes |
|---|---|---|---|
| `v0.1.0` | 2026-11-24 | `195fb69` | First public release: BCD redstone clocks, deferred cleanup, diagnostics. GitHub only. |
| `v0.9.0` | 2026-11-27 | `d6c5bbc` | Two read-only PlaceholderAPI placeholders (`%realtime_update_available%`, `%realtime_latest_version%`) plus a documentation resync: the command surface count, the long-gone `/rtime now`, the missing `realtime.update` permission, and a new `SECURITY.md` section describing exactly what the updater does on the network. No behaviour change. GitHub 2,350,085 B sha256 `51379a0d…`. |
| `v0.8.0` | 2026-11-27 | `c630def` | Self-update through the server update folder: `/rtime update` checks Hangar (GitHub fallback), verifies the published checksum and stages the jar in `plugins/update/`, which Paper applies at the next start. No hot swap, no restart. GitHub 2,348,809 B sha256 `d8f69b93…`; Hangar + Modrinth + both page syncs unattended. Real-server acceptance against the real Hangar API is in `docs/operations/acceptance-report-0.8.0.md`. |
| `v0.7.0` | 2026-11-27 | `5e44ffd` | Documentation and release automation only — no gameplay change. Publish job is re-runnable, Hangar main page **and** the Modrinth description/body now sync from `docs/operations/platform-listings.md` on every release, and `platform-audit.sh` audits Hangar keywords too. GitHub 2,183,111 B sha256 `d4f3dae1…b8dc504`; first release where every publish step (GitHub, Hangar, both page syncs, Modrinth) succeeded unattended. |
| `v0.6.0` | 2026-11-26 | `f1607f6` | **Breaking**: BCD encoding removed (decoder delay made the display unreadable), lectern-only output, `second` by default, `off` keeps the lecterns, schema **v7** with legacy BCD entries skipped and reported, command surface rewritten (`/rtime` + `/rtime <name>`, `create`/`on`/`off`/`place`/`remove`, `status`/`cleanup`/`help`/`reload`, keyword options, unique names). GitHub 2,183,111 B sha256 `35d01eb5…acd8239`; the workflow's Hangar step died on a transient Maven Central 403, so Hangar (0.6.0, sha256 matches) and Modrinth (`DXRcGrrw`) were published by hand; the workflow is now re-runnable (PR #79). |
| `v0.5.0` | 2026-11-26 | `d507280` | Five flat commands (`create`/`info`/`on|off`/`remove`) with `device …` kept as a compatibility alias, one-command clock creation with display blocks, per-slot placement for `manual` builds (schema **v6**, `unplaced`), redstone module on by default, module/limit state logged. GitHub 2,156,469 B sha256 `5ed45650…4b1ef0d`; **Hangar published automatically for the first time** (sha256 matches); Modrinth rejected the upload (`missing field dependencies`) and was backfilled by hand, workflow fixed in PR #74. |
| `v0.4.0` | 2026-11-25 | `0fa54cc` | Adds `device cleanup dismiss` for jobs whose remaining entries are all `NEEDS_ATTENTION` (terrain the plugin never placed), the `device.demo.hint` message, and the consolidated [usage guide](../usage.md). No schema change (v5). GitHub only. |
| `v0.3.0` | 2026-11-25 | `9093b76` | Bug-fix release: cleanup queue counts and retries demo display blocks, and the periodic scan indexes them over the stored list (0.2.0 could leave a job stuck). No configuration or store change (schema v5). GitHub only. |
| `v0.2.0` | 2026-11-25 | `e0632f5` | Two encodings (BCD + analog lecterns), in-game device authoring with split permissions, spaced/grouped default layout, `device demo` teaching builds, schema v5. GitHub only; Hangar/Modrinth still unconfigured. |

Both releases used the protected `release` environment (required reviewer, `v*` tag policy) with
`RELEASE_PUBLISH_ENABLED=true`, and both artifacts were verified against a local reproducible build
(`v0.2.0`: 2,132,970 B, sha256 `f818b9a7…96dc1e`; `v0.3.0`: 2,133,550 B, sha256 `bf8c713b…5727ce`; `v0.4.0`: 2,136,461 B, sha256 `8531becc…4ae77bbc`).

## Dry runs

The workflow also accepts `workflow_dispatch` with `ref`, `version` and `dry_run` inputs:

```bash
gh workflow run release.yml -f ref=main -f version=0.5.0 -f dry_run=true
```

A dry run resolves the version, checks the distribution metadata, builds and inspects the shaded JAR, runs the
distribution preflight against the public APIs, and writes a summary — but creates no GitHub Release and uploads
nothing. Its `publish` job uses the unprotected `release-dry-run` environment, so it needs no approval and never sees
the publishing tokens (the `release` environment stays restricted to `v*` tags). Use it to verify Hangar/Modrinth
wiring without burning a version number or pushing a tag.

## Reproducibility and artifact integrity

Gradle archive tasks disable preserved file timestamps and use reproducible file ordering. Each release artifact is named `RealTime-VERSION.jar` and is accompanied by a `.sha256` file. Verify it after download with:

```bash
sha256sum --check RealTime-VERSION.jar.sha256
```

The GitHub Release is not published by this task itself; the repository owner must configure the protected environment and explicitly enable publishing.
