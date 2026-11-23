# Development Setup

## Requirements

- JDK 25.
- Gradle 9.2.1 or a compatible Gradle 9.x release.
- Git and network access to Maven Central and PaperMC Maven.

## Bootstrap

The initial scaffold does not include the binary Gradle Wrapper JAR. Until official wrapper artifacts are generated, install Gradle locally and use the scripts. Do not commit a fabricated wrapper JAR.

```bash
./scripts/doctor.sh
./scripts/verify.sh
```

## IDE

Import as a Gradle Kotlin DSL project and use JDK 25 for Gradle and project toolchains.

## Paper API

PaperMC documents `io.papermc.paper:paper-api:26.3.build.+` for the 26.3 line. Generate and commit dependency lock state after a successful resolution. Review lockfile changes during dependency updates.

## Server testing

Use disposable Paper/Folia servers, synthetic worlds, and test data. Never use production worlds or credentials as test fixtures.
