# Release Process

RealTime uses SemVer tags prefixed with `v`. The release workflow accepts stable tags such as `v0.1.0` and prerelease tags such as `v0.2.0-rc.1`. Build metadata suffixes are intentionally not accepted.

## Prepare a release

1. Update `CHANGELOG.md` with a non-empty section whose heading exactly matches `## [MAJOR.MINOR.PATCH]` or the full prerelease version.
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

## Optional distribution publishing

Hangar publishing uses the official PaperMC Gradle publish plugin and is skipped unless `HANGAR_PROJECT_ID` is configured as an environment variable and `HANGAR_API_TOKEN` is configured as an environment secret. The token should be scoped to the RealTime project and the `create_version` permission. Stable versions publish to Hangar's `Release` channel; prereleases are not sent to Hangar automatically.

Modrinth publishing is skipped unless `MODRINTH_PROJECT_ID` and the environment secret `MODRINTH_TOKEN` are configured. Use a token with only the version-create scope. The workflow uploads the same shaded JAR and matching changelog section.

All distribution credentials are read only inside the protected `release` job. Never add them to pull-request workflows, repository files, Gradle properties, or logs. Do not enable `RELEASE_PUBLISH_ENABLED` until the environment protection and project identifiers have been reviewed.

## Reproducibility and artifact integrity

Gradle archive tasks disable preserved file timestamps and use reproducible file ordering. Each release artifact is named `RealTime-VERSION.jar` and is accompanied by a `.sha256` file. Verify it after download with:

```bash
sha256sum --check RealTime-VERSION.jar.sha256
```

The GitHub Release is not published by this task itself; the repository owner must configure the protected environment and explicitly enable publishing.
