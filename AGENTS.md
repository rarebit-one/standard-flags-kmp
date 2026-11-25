# AGENTS.md

Kotlin Multiplatform client for [standard_flags](https://github.com/rarebit-one/standard_flags).
This is a public repository: keep code, docs, commits and PRs generic.

## Workflow

- Work in a linked worktree (`git worktree add .worktrees/<slug> -b <branch> origin/main`), never in the main checkout.
- Commits are signed. Changes go through pull requests; `main` is protected.
- Run `./gradlew check` before pushing. On macOS it also runs the iOS simulator tests; `./gradlew iosSimulatorTests` runs only those.

## Layout

Gradle modules share `gradle/libs.versions.toml` and the coordinates in `gradle.properties` (`GROUP=one.rarebit.flags`).

## Releasing

Publishing goes through CI, never from a laptop. Pushing a `v*` tag runs `.github/workflows/release.yml`, which calls the shared `reusable-maven-central-release.yml` in `rarebit-one/.github`. The tag must match `VERSION_NAME` in `gradle.properties`, and `CHANGELOG.md` must have a `## [x.y.z]` entry. Check the artifacts locally with `./gradlew publishToMavenLocal`.
