# Development Setup

## Requirements

- JDK 25.
- Git.
- Network access to Maven Central, PaperMC Maven, and services.gradle.org for the first Wrapper distribution download.

## Bootstrap

The repository includes the official Gradle Wrapper scripts and JAR from Gradle 9.2.1. Use the wrapper; it downloads and verifies the configured Gradle distribution.

```bash
./scripts/doctor.sh
./scripts/verify.sh
```

The wrapper distribution is pinned to Gradle 9.2.1 in `gradle/wrapper/gradle-wrapper.properties`. Do not replace the wrapper JAR with a hand-built or placeholder binary.

## IDE

Import as a Gradle Kotlin DSL project and use JDK 25 for Gradle and project toolchains.

## Paper API

PaperMC documents `io.papermc.paper:paper-api:26.3.build.+` for the 26.3 line. Generate and commit dependency lock state after successful resolution. Review lockfile changes during dependency updates.

## Server testing

Use disposable Paper/Folia servers, synthetic worlds, and test data. Never use production worlds or credentials as test fixtures.
