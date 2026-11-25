# AGENTS.md

Kotlin Multiplatform client for [standard_flags](https://github.com/rarebit-one/standard_flags).
This is a public repository: keep code, docs, commits and PRs generic.

## Workflow

- Work in a linked worktree (`git worktree add .worktrees/<slug> -b <branch> origin/main`), never in the main checkout.
- Commits are signed. Changes go through pull requests; `main` is protected.
- Run `./gradlew check` before pushing. On macOS it also runs the iOS simulator tests; `./gradlew iosSimulatorTests` runs only those.
- `check` also compares each Kotlin module's public API with its `api/` dump. After an intended API change, run `./gradlew updateKotlinAbi` on macOS (so the iOS klib dump is complete) and commit the dumps.
- For configuration classes and the rest of the non-result API, a removed `<init>` (or any removed line) in the dump diff is a binary break for apps built against an earlier release. When adding a constructor parameter to such a class, keep the old constructor as a secondary one (`@Deprecated(level = DeprecationLevel.HIDDEN)`) so those callers still link. Result and value types (the README's Compatibility list) may gain fields in a minor release, so their `copy`, `componentN` and constructor lines may change in the dump; that is allowed by the policy, not a break to work around.

## Layout

Gradle modules share `gradle/libs.versions.toml` and the coordinates in `gradle.properties` (`GROUP=one.rarebit.flags`).

- `standard-flags-core`: wire model, validation, reads, SemVer ranges, last-good store, ETag. No HTTP.
- `standard-flags-ofrep`: the Ktor OFREP client and `StandardFlagsClient` (the cache and apply policy).
- `standard-flags-openfeature`: the OpenFeature provider. Keep it optional: nothing else depends on it.
- `standard-flags-status`: `/client/status` and 426 detection.
- `standard-flags-gradle-plugin`: the contract check, in Java so it adds no Kotlin stdlib to app builds.
- `conformance/`: the gem's wire fixtures, vendored at the commit in `PIN.json`. Never edit them by hand: run `scripts/sync-conformance` (`--ref <ref>` moves the pin). The core module embeds them at build time and runs them on every target.

The wire contract lives in the gem (`conformance/README.md` there and here). When it changes, bump the pin, then make the runner pass.

## Releasing

Publishing goes through CI, never from a laptop. Pushing a `v*` tag runs `.github/workflows/release.yml`, which calls the shared `reusable-maven-central-release.yml` in `rarebit-one/.github`. The tag must match `VERSION_NAME` in `gradle.properties`, and `CHANGELOG.md` must have a `## [x.y.z]` entry. Check the artifacts locally with `./gradlew publishToMavenLocal`.
