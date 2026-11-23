# Release Process

Use Semantic Versioning after the first stable release; use 0.x.y during pre-release development. Before release, verify CI, Paper/Folia runtime evidence, changelog, configuration/data migrations, and shaded JAR contents.

Hangar and Modrinth are the intended distribution channels. Add project identifiers and narrowly scoped publishing tokens as GitHub Actions secrets only after project pages exist. Never store tokens in repository files. Initial scaffold does not publish to these platforms.
