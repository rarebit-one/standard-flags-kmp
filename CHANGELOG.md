# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0] - 2026-11-26

The first release: a Kotlin Multiplatform client for standard_flags, published to Maven Central under `one.rarebit.flags`.

### Supported targets

- Android, iosArm64, iosSimulatorArm64 and the JVM (for tests), built with Kotlin 2.4, Ktor 3.6, kotlinx.serialization and coroutines.
- Modules: `standard-flags-core`, `standard-flags-ofrep`, `standard-flags-status`, `standard-flags-openfeature` (optional) and the Gradle plugin `standard-flags-gradle-plugin` (`one.rarebit.flags.contract`).
- Each Kotlin module's public API is recorded in `api/` and checked by `check`. Configuration classes (`PayloadLimits`, `ApplyPolicy`, `ClientInfo`) are regular classes rather than data classes, so a field can be added later without changing `copy` or `componentN`. Result and value types (`FlagEntry`, `FlagRead`, `StatusDetails`, `StoredPayload`, and the status and outcome cases) are data classes that may gain fields in minor releases: read them by property name, and don't destructure them or rely on `copy`.

### Added

- `standard-flags-core`: the wire model and validation, with no HTTP.
  - OFREP bulk payloads parsed as strict RFC 8259 JSON in UTF-8 (tolerant of unknown fields) and validated against `PayloadLimits` (`maxBytes`, `maxFlags`, `maxNesting` up to `MAX_NESTING_CAP`). A rejected payload never replaces the last good one (`FlagCache`, `PayloadResult`, `RejectReason`).
  - `BooleanFlag` and `StringFlag` declarations with code defaults, ceilings (the server may only lower a ceiling flag), allowed string values and declared kill switches. Reads are tri-state (`FlagState`: On, Off, NotLoaded) and per flag: a missing, errored, valueless, mistyped or undeclared value reads as the code default, with the reason in `FlagRead`.
  - SemVer `Version`, `VersionRange` and `BuildRange` in the server's neutral syntax.
  - `LastGoodStore`, with in-memory and string adapters. A stored payload is keyed by context and build, and one saved for another account or build is never applied.
  - The RFC 8785 ETag (`Etag`, `Jcs`, `Sha256`) in common code.
- `standard-flags-ofrep`: the OFREP client and the client-side cache.
  - `OfrepClient`: JSON POST with `X-Client-*` headers (also the `ClientHeaders` Ktor plugin), ETag revalidation with If-None-Match and 304, and a streamed body read capped by the payload limit. An If-None-Match the server keeps refusing is dropped and remembered across restarts.
  - Jittered exponential `Backoff` honouring `Retry-After` in seconds or any HTTP-date form; fetching stops on 401/403 until the context changes.
  - `StandardFlagsClient`: the last good payload at launch; fresh values apply at the next launch (`ApplyPolicy`), while kill switches (reason `KILL_SWITCH`, or a declared kill switch) apply live and never wait on the store. Also bootstrap seeding, context switching that drops the previous account's values at once, store failures reported without breaking reads, no lock held across I/O, and concurrent refreshes sharing one fetch.
- `standard-flags-status`: `ClientStatusClient` exposes `/client/status` as a `StateFlow` (ok, upgrade recommended with its enforcement date, upgrade required, unknown, with the store URL). The `UpgradeRequiredDetector` plugin flips it to upgrade required when any API call answers 426 with the server's `client_upgrade_required` error; a bare 426 is ignored, and an unknown build is never blocked.
- `standard-flags-openfeature`: `StandardFlagsProvider` for the OpenFeature kotlin-sdk 0.9. It reads through the client's declarations (ceilings and allowed values hold whatever default the caller passes), maps evaluation failures to OpenFeature error codes, passes reasons through and emits change events for the wires that changed.
- `standard-flags-gradle-plugin`: `one.rarebit.flags.contract` with the `checkFlagsManifest` task, run by `check`. It reads manifest versions 1 and 2 as the server reads them, and fails when the app reads a wire its audience isn't exposed, a wire's type differs, or the server can send a string value the app doesn't declare. An optional `baselineManifest` applies the server's manifest-check semantics (removed or retyped wires, audiences made private). Reading a wire whose tombstone `until` date has passed fails, and so, with a baseline, does a tombstone `until` the server moved earlier once that date passes; the check's UTC date is computed when the task runs, and `-PstandardFlags.today` reproduces a check on another day. The supported surface is the plugin id, the `standardFlags { }` block, the `checkFlagsManifest` task and the `standardFlags.today` property; the plugin's Java classes are internal.
- Conformance: the standard_flags wire fixtures (version ranges, client state, delivery bodies and ETags, client status, manifest check), vendored at a pinned gem commit with `scripts/sync-conformance` and run on the JVM, Android and the iOS simulator.
