# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `standard-flags-core`: the OFREP bulk wire model with strict RFC 8259 parsing (tolerant of unknown fields) and payload validation (`PayloadLimits`, `FlagCache`); `BooleanFlag`/`StringFlag` declarations with ceilings, allowed values and declared kill switches; `FlagState` (On, Off, NotLoaded) and `FlagRead`; SemVer `Version`, `VersionRange` and `BuildRange` in the neutral syntax; `LastGoodStore` with in-memory and string adapters, keyed by context and build; the RFC 8785 ETag (`Etag`, `Jcs`, `Sha256`).
- `standard-flags-ofrep`: `OfrepClient` (JSON POST, `X-Client-*` headers, ETag / 304, a streamed body read capped by `Content-Length` and length), the `ClientHeaders` Ktor plugin, jittered `Backoff` honouring `Retry-After`, and `StandardFlagsClient` (apply-at-launch, live kill switches that never wait on the store, bootstrap seeding, stop on 401/403 until the context changes, no lock held across I/O, shared concurrent fetches).
- `standard-flags-openfeature`: `StandardFlagsProvider` for the OpenFeature kotlin-sdk 0.9.
- `standard-flags-status`: `ClientStatusClient` (`/client/status` as a `StateFlow`) and the `UpgradeRequiredDetector` plugin for 426 answers.
- `standard-flags-gradle-plugin`: `one.rarebit.flags.contract` with the `checkFlagsManifest` task (manifest versions 1 and 2, wire exposure, types and string values).
- Kotlin ABI validation: each module's public API is recorded in `api/` and checked by `check`.
- The standard_flags wire conformance fixtures, vendored at a pinned gem commit with `scripts/sync-conformance`, and a runner on the JVM, Android and the iOS simulator.
