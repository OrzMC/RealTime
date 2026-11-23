# PR7 — Release Automation

- Status: Ready
- Priority: P2
- Depends on: PR4, PR5, PR6 (all merged)

## Objective

Create a safe, tag-driven release pipeline for the independently buildable RealTime plugin. Produce a versioned, reproducible shaded JAR, validate release metadata, attach checksums and changelog notes to a GitHub Release, and keep optional distribution publishing behind explicit configuration and protected credentials.

## Scope

- Accept only SemVer tags in the form `vMAJOR.MINOR.PATCH` or `vMAJOR.MINOR.PATCH-prerelease`.
- Require a matching non-empty `CHANGELOG.md` section before building.
- Pass the tag version to Gradle through `-PprojectVersion`; the plugin descriptor and artifact name must use that version.
- Configure archive tasks for reproducible file ordering and timestamps.
- Run the same project verification and shaded-JAR inspection used by CI.
- Generate a SHA-256 checksum and extract release notes from the matching changelog section.
- Build on tag pushes only. Do not expose publishing secrets to pull-request workflows or fork code.
- Create the GitHub Release only in a dedicated `release` environment and only when the repository variable `RELEASE_PUBLISH_ENABLED=true` is explicitly set.
- Document required GitHub environment protection (required reviewers and tag-only deployment branches).
- Keep Hangar/Modrinth publishing disabled until project IDs and narrowly scoped tokens are configured; any future upload steps must run only in the protected release job.

## Constraints

- Do not publish a real release or configure repository secrets/settings as part of this task.
- Never commit tokens, project secrets, or environment-specific credentials.
- Preserve independent build/release behavior; do not introduce a dependency on the parent OrzMC repository.
- Do not make release workflows run on `pull_request` events.

## Acceptance

- Release tag parser accepts valid stable and prerelease SemVer tags and rejects malformed tags.
- A matching changelog section is mandatory and must contain release notes.
- A tagged build emits `RealTime-${version}.jar` with deterministic archive ordering/timestamps.
- CI verification, shaded-JAR inspection, and runtime smoke workflows remain green.
- Release notes and SHA-256 checksum are generated from the exact tagged source.
- Workflow permissions are least-privilege; only the protected release job receives `contents: write`.
- GitHub Release creation is disabled by default and requires both the explicit repository variable and the protected `release` environment.
- Hangar/Modrinth tokens are never available to PR jobs; publishing remains disabled unless the required IDs/tokens are configured in the protected environment.
- Local shell tests cover tag validation and changelog extraction.

## Deliverables

- Tag validation and changelog extraction scripts with tests.
- Reproducible Gradle archive configuration.
- Tag-triggered release workflow and release-process documentation.
- Updated task and roadmap status backed by CI evidence.
