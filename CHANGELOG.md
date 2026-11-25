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
- `standard-flags-gradle-plugin`: `one.rarebit.flags.contract` with the `checkFlagsManifest` task (manifest versions 1 and 2, wire exposure, types and string values). Reading a deprecated wire fails once its tombstone's `until` has passed. The check's date (UTC) is computed when the task runs, so the configuration cache never freezes it; the `standardFlags.today` Gradle property sets it to reproduce a check.
- Kotlin ABI validation: each module's public API is recorded in `api/` and checked by `check`.
- `PayloadLimits.maxNesting` (default 100, at most `MAX_NESTING_CAP` = 1000), lone and reversed surrogate escapes rejected (ASCII hex digits only), and the shared version rule (1 to 256 printable ASCII characters, applied to fetched and stored payloads and to ETag headers), matching the strict-JSON `client_state.json` cases.
- `StandardFlagsClient` stops sending an If-None-Match after three refusals in a row (400, 414, 431, or a header the HTTP stack refuses), and keeps the refusal in `StoredPayload.refusedEtag` so a restart doesn't send it again.
- `ManifestCheck` and the contract plugin's optional `baselineManifest`: the shared `manifest_check.json` semantics (stale, removed wires, type changes, audiences made private, invalid manifests). Manifests are read as the server's reader reads them, refusing what it refuses (required fields, boolean `public`, value and client types, calendar dates, `serve` with `until` and `until_clients`, wires listed twice), and a version 1 baseline is read so the upgrade to version 2 is still checked. The server's warnings (an earlier `until`, a Kotlin constant collision) are logged.
- The standard_flags wire conformance fixtures, vendored at a pinned gem commit with `scripts/sync-conformance`, and a runner on the JVM, Android and the iOS simulator.
