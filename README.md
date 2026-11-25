# standard-flags-kmp

Kotlin Multiplatform client for [standard_flags](https://github.com/rarebit-one/standard_flags). Android and iOS apps use it to read the flags the server exposes to them over [OFREP](https://openfeature.dev/specification/appendix-c), with a last-good cache, tri-state reads, apply-at-launch with live kill switches, and upgrade status.

Targets: Android, iosArm64, iosSimulatorArm64, and the JVM (for tests). Built with Kotlin 2.4, Ktor 3.6, kotlinx.serialization and coroutines.

| Module | What it is |
|---|---|
| `standard-flags-core` | Wire model and payload validation, `FlagState` (On, Off, NotLoaded) and typed reads, ceilings, SemVer `Version` and the neutral `VersionRange`/`BuildRange`, `LastGoodStore`, the RFC 8785 ETag. No HTTP. |
| `standard-flags-ofrep` | The Ktor OFREP client: `X-Client-*` headers (also a plugin for your own client), ETag / 304, jittered backoff with `Retry-After`, stop on 401/403, apply-at-launch, bootstrap seeding. |
| `standard-flags-openfeature` | An OpenFeature kotlin-sdk `FeatureProvider` over the OFREP client. Optional: skip it to bind your own seams to core. |
| `standard-flags-status` | `/client/status` as a `StateFlow` (ok, upgrade recommended, upgrade required, unknown), and a plugin that catches 426 answers. |
| `standard-flags-gradle-plugin` | `checkFlagsManifest`: fails the build when the app reads a wire its audience isn't exposed, or a wire's type changed. |

## Installation

Not published yet. Once it is, the artifacts are on Maven Central under `one.rarebit.flags`:

```toml
# gradle/libs.versions.toml
[versions]
standardFlags = "0.1.0"

[libraries]
standard-flags-ofrep = { module = "one.rarebit.flags:standard-flags-ofrep", version.ref = "standardFlags" }
standard-flags-status = { module = "one.rarebit.flags:standard-flags-status", version.ref = "standardFlags" }
standard-flags-openfeature = { module = "one.rarebit.flags:standard-flags-openfeature", version.ref = "standardFlags" }

[plugins]
standardFlagsContract = { id = "one.rarebit.flags.contract", version.ref = "standardFlags" }
```

```kotlin
// shared/build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.standard.flags.ofrep)   // brings core
            implementation(libs.standard.flags.status)
        }
    }
}
```

The Gradle plugin resolves from Maven Central, so add `mavenCentral()` to `pluginManagement.repositories` in `settings.gradle.kts`.

**Working against a local checkout.** Use a composite build instead of publishing snapshots. In the app's `settings.gradle.kts`:

```kotlin
val localFlags = file("../standard-flags-kmp")
if (localFlags.isDirectory) includeBuild(localFlags)
```

Gradle substitutes `one.rarebit.flags:*` with the local modules. For the contract plugin, add the same `includeBuild` inside `pluginManagement { }`. `./gradlew publishToMavenLocal` here works too, for tools that need a Maven repository.

## Quick start

Declare the wires the app reads, with their code defaults. A wire is the name the server exposes to your audience, never the server's flag key.

```kotlin
object Flags {
    val Chat = BooleanFlag("chat_enabled", default = false)
    val Purchases = BooleanFlag("purchases", default = false, ceiling = true)   // the server may only lower it
    val Maintenance = BooleanFlag("maintenance", default = false, killSwitch = true)
    val HomeHero = StringFlag("homeHero", default = "control", values = setOf("control", "savings"))
    val all = listOf(Chat, Purchases, Maintenance, HomeHero)
}
```

Build the client once, in shared code:

```kotlin
val client = ClientInfo(platform = "android", version = BuildConfig.VERSION_NAME, build = BuildConfig.VERSION_CODE.toLong())

val http = HttpClient {
    install(ClientHeaders) { client(client) }   // every API call carries X-Client-*
    install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    // your auth plugin here; the OFREP endpoint sits behind the app's own authentication
}
check(client.isRecognised) { "the server would treat $client as an unknown build" }   // in a debug build or a test

val flags = StandardFlagsClient(
    fetcher = OfrepClient(http, "https://api.example.com/ofrep/v1/evaluate/flags", client),
    definitions = Flags.all,
    store = lastGoodStore,          // see below
    contextKey = session.accountId, // whose flags these are
)

flags.load()                        // at launch, before the first read: the cached values
scope.launch { flags.refresh() }    // at launch and on every foreground
```

Read without suspending:

```kotlin
when (flags.state(Flags.Chat)) {
    FlagState.On -> showChat()
    FlagState.Off -> hideChat()
    FlagState.NotLoaded -> showChatFromDefault()   // nothing usable from the server: the code default applies
}
val hero: String = flags.value(Flags.HomeHero)
val read = flags.read(Flags.Chat)   // value, loaded, reason (KILL_SWITCH, STATIC, …), variant, and why it fell back
```

On sign-in, sign-out or an account switch, switch the HTTP client's credentials first, then call `flags.setContext(newAccountId)` and `refresh()`. The old account's values are dropped at once, even while a fetch is still running; its late answer is thrown away. (Calling `setContext` before switching credentials would let a fetch in between store the old account's flags under the new key.)

`ClientInfo.isRecognised` matters because the server never errors on a bad platform or version: it quietly treats the build as unknown, which loses every `min_client` flag.

**Android.** Back the store with DataStore or SharedPreferences, and refresh on foreground, for example from a `ProcessLifecycleOwner` observer's `onStart`:

```kotlin
val lastGoodStore = StringLastGoodStore(
    read = { prefs.getString("flags.last_good", null) },
    write = { value -> prefs.edit { if (value == null) remove("flags.last_good") else putString("flags.last_good", value) } },
)
```

**iOS.** The library is consumed through your shared KMP module (there is no separate Swift package). In `iosMain`, use the Darwin engine and `NSUserDefaults`, and call `refresh()` from the app's `scenePhase == .active` handler through a shared function:

```kotlin
val lastGoodStore = StringLastGoodStore(
    read = { NSUserDefaults.standardUserDefaults.stringForKey("flags.last_good") },
    write = { value -> NSUserDefaults.standardUserDefaults.setObject(value, forKey = "flags.last_good") },
)
```

**Bootstrap.** If a response the app fetches at launch embeds the flags (the server's `standard_flags_payload(audience, format: :ofrep)`, for example inside `/me`), seed from it to save a round trip: `flags.seed(meResponse.flagsJson)`. The next `refresh()` sends its version as `If-None-Match` and usually gets a 304.

**Pre-auth audiences.** For an endpoint mounted with `anonymous: true`, pass `targetingKey = { installId }` to `OfrepClient`. Install IDs must be random and unguessable, and the answers are public.

## Trust and caching model

- **The server decides; the client only reads.** Payloads carry evaluated values, never rules, allowlists or the names of flags the audience can't see. The client never evaluates targeting.
- **Validate, then replace.** A payload replaces the last good one only when it passes the [conformance rules](conformance/README.md): at most `PayloadLimits.maxBytes` and `maxFlags`, strict RFC 8259 JSON in UTF-8 (no `NaN`, unquoted words, single quotes, leading zeros, lone or reversed surrogate escapes or trailing bytes, and at most `maxNesting` containers deep, 100 by default), an object with a `flags` array of objects with unique non-empty string keys, and `metadata` an object when present. Anything else is rejected, the last good payload stays, and the client backs off.
- **One size limit, enforced while streaming.** The `StandardFlagsClient`'s `PayloadLimits` apply to fetched, seeded and stored payloads alike. The fetch streams the body: a `Content-Length` over the limit is refused without reading, and a body without one stops being read one byte past the limit, so an oversized answer never sits in memory.
- **Per-flag fallback.** A missing entry, an evaluation failure, an entry without a value, a value of the wrong type, or a string outside the declared `values` all read as the code default with `FlagState.NotLoaded`. One bad flag never affects another.
- **Ceilings.** A `BooleanFlag(ceiling = true)` reads `default && server value`, so the server can switch it off but never on, for example purchases on a platform whose store rules forbid them.
- **The cache belongs to one context and one build.** The `LastGoodStore` keeps the body, the `contextKey` it was fetched for and the build (`ClientInfo.fingerprint`: platform, version, build). A stored payload for another key, or saved by another build, is never applied: the server filters by build (`min_client`, deprecated wires), so an old build's answer could withhold or serve the wrong wires. After an upgrade the app starts on code defaults, and the first payload for the new build applies live. `setContext` drops the old values at once and clears a stored payload that belongs to another context. A store that throws never breaks the client: the failure shows in `flags.storeError`, and the in-memory values stay.
- **Kill switches never wait on the disk.** A fresh payload is applied (kill switches live) before it is saved. Only a saved payload becomes the pending one and supplies the next If-None-Match, so after a failed save the next fetch gets the full answer again rather than a 304.
- **ETags.** The server's ETag hashes the evaluated answer (the audience and entries, RFC 8785), never a user ID or a config revision, so a 304 is only possible when the body would be identical. A `metadata.version` or ETag header that isn't a valid entity tag (1 to 256 printable ASCII characters other than a space or `"`) is ignored (no If-None-Match is sent), including the version of a stored payload loaded at launch. When requests that carry an If-None-Match keep failing, the client stops sending it: at once on a 431, and after three in a row of client-side failures or other 4xx answers (not 408 or 429), so a bad value can't wedge fetching.
- **Backoff.** Failures, 5xx, 429 and rejected payloads back off exponentially with jitter (`Backoff`, 2 s doubling to 5 min by default), with `Retry-After` (seconds or any HTTP-date form) honoured as a floor, capped at an hour. A 401 or 403 stops fetching until `setContext`.
- **No lock across I/O.** The client's state lock is never held during a fetch or a store call. Concurrent `refresh()` calls share one fetch. Install a request timeout on the HTTP client all the same (`HttpTimeout`), so a stalled request doesn't hold up the next refresh.

## When fresh values apply

Values read at launch are the cached ones. A fresh payload is saved at once but applies at the **next launch**, so a screen never changes under the user. Two exceptions apply live:

1. **Kill switches.** An entry the server sends with reason `KILL_SWITCH` applies immediately, so a remote kill lands at the next foreground, not the next launch. Releasing it (the reason goes back to anything else) waits for the next launch. A `BooleanFlag(killSwitch = true)` applies live in both directions.
2. **The first payload.** When nothing was loaded at launch (a first run, or right after `setContext`), the first payload applies at once.

`ApplyPolicy(freshValuesAtNextLaunch = false)` applies every payload immediately; `firstPayloadAppliesLive = false` keeps code defaults until the next launch. `flags.payload` is a `StateFlow` of what code reads, and `flags.pending` holds the payload waiting for the next launch.

## Upgrade status

```kotlin
val status = ClientStatusClient(http, "https://api.example.com/client/status", client)
val api = HttpClient { install(UpgradeRequiredDetector) { this.status = status } }

scope.launch { status.check() }   // at launch and on foreground
status.status.collect {
    when (it) {
        is ClientStatus.UpgradeRequired -> showBlockingUpgrade(it.details.storeUrl)
        is ClientStatus.UpgradeRecommended -> showNudge(it.details.storeUrl, enforceAt = it.enforceAt)   // a YYYY-MM-DD date, or null
        is ClientStatus.Ok, is ClientStatus.Unknown -> Unit
    }
}
```

A failed check keeps the last known status, and an unknown build is never blocked. `enforceAt` is the UTC date enforcement starts (`YYYY-MM-DD`), not an instant. `UpgradeRequiredDetector` flips the status when any API call answers 426 with the server's `client_upgrade_required` error; a bare 426 is ignored. It reads the 426 body, which an ordinary Ktor call keeps for the app too, but a streaming call (`prepare*().execute { }`) doesn't.

## OpenFeature

```kotlin
OpenFeatureAPI.setProviderAndWait(StandardFlagsProvider(flags), ImmutableContext(targetingKey = accountId))
val chat = OpenFeatureAPI.getClient().getBooleanValue("chat_enabled", false)
```

The provider reads through the client's declarations, so allowed values hold, and a ceiling flag is capped by its declared default whatever default the caller passes. It maps evaluation failures to OpenFeature error codes, passes the server's reason through (including `KILL_SWITCH`), and emits a configuration-changed event naming the wires that changed.

The targeting key is the client's context key: `initialize` hands it to `setContext` before loading, so the payload saved for it is the launch value, and a new targeting key calls `setContext`. Build the client with the same key, or none. The first fetch runs in the provider's scope: `initialize` waits up to `initialFetchTimeout`, and a slower fetch still lands afterwards.

## Contract check

The server commits a flags manifest (`bin/rails standard_flags:manifest`). Copy or reference it from the app and declare what the app reads:

```kotlin
plugins { id("one.rarebit.flags.contract") }

standardFlags {
    manifest = rootProject.file("contracts/flags.manifest.json")
    audience = "mobile"
    wire("chat_enabled", "boolean")
    stringWire("homeHero", "control", "savings")   // with the values the app understands
    // or wiresFile = file("flags.wires.json"):
    // {"chat_enabled": "boolean", "homeHero": {"type": "string", "values": ["control", "savings"]}}
}
```

Optionally, point `baselineManifest` at the manifest your shipped builds were made against. The check then also fails when the current manifest would break them (a wire removed or retyped, a public audience made private), with the server's `standard_flags:manifest:check` semantics. Both manifests are read as the server reads them, so a hand-edited file the server would refuse (`{"version":2}`, an audience without a boolean `public`, a date that isn't a calendar date) fails the check rather than passing. A version 1 baseline (standard_flags 0.3.0 and earlier) is read too, so removals across the upgrade to version 2 are still caught.

`./gradlew check` then runs `checkFlagsManifest`. It fails when a wire isn't exposed to the audience (naming any audience that does see it), when its type differs, or when the server can send a string value the app doesn't declare (the app would read its code default). It warns when the app declares a value the server never sends, and when a wire is only served by a deprecated tombstone, with its end date (`failOnDeprecated = true` makes that an error too). Once that date has passed the server no longer sends the wire, so reading it fails. Dates are UTC and computed when the task runs, even from the configuration cache; `-PstandardFlags.today=YYYY-MM-DD` reproduces a check on another day. It reads manifest versions 1 and 2.

## Conformance

`conformance/` vendors the gem's wire-scope fixtures at the commit pinned in `conformance/PIN.json`. The tests run them on the JVM, Android and the iOS simulator: version and build ranges, payload in → reads out (through `FlagCache` and again through `StandardFlagsClient`), every golden delivery body and its ETag, every `wire/client_status.json` status body, and every `wire/manifest_check.json` case through the Gradle plugin's `ManifestCheck`. A test fails if a vendored fixture has no runner. `scripts/sync-conformance --ref main` moves the pin; CI runs `scripts/sync-conformance --check`, which also refuses any file in `conformance/` the pin doesn't list.

## Development

```bash
./gradlew check                 # every test; on macOS this includes the iOS simulator
./gradlew iosSimulatorTests     # only the iOS simulator tests
./gradlew publishToMavenLocal   # all modules into ~/.m2
./gradlew updateKotlinAbi       # after an intended public API change; check fails on any other
```

The public API of each Kotlin module is recorded in its `api/` folder (Kotlin's ABI validation), and `check` fails when it changes unexpectedly.

Releases go through CI: a `v*` tag matching `VERSION_NAME` publishes to Maven Central. See [AGENTS.md](AGENTS.md).

## License

MIT. See [LICENSE](LICENSE).
