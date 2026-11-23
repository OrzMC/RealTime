# ADR 0002: Separate core logic from Minecraft APIs

- Status: Accepted
- Date: 2026-11-23

## Context

Time calculations and encoding rules should be testable without a Minecraft server. Paper and Folia have distinct scheduling constraints.

## Decision

Use `realtime-core` for pure Kotlin/JVM logic and `realtime-paper` for plugin lifecycle and platform integration. Core must not depend on Bukkit, Paper, Folia, or PlaceholderAPI.

## Consequences

Core tests use fixed `java.time.Clock`; platform behavior requires integration/runtime tests and cannot be proven by core tests alone.
