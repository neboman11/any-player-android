# Provider data layer

`ProviderDataModule` supplies the singleton HTTP client, secure connection and settings stores,
Spotify PKCE/token services, provider registry, auth repository, and Room-backed catalog.
The application must provide `@Named("spotifyClientId") String`; it is deliberately not a
library BuildConfig value.

Startup should call `LegacyCacheImporter.runOnce()` on its background restore path. The
import is transactional, preserves newer normalized caches, removes only legacy provider/union
cache rows, and never parses JSON in a Room migration. Room v3 schema remains unchanged.

Cache owner keys are `p:${SourceType.name}:<playlistId>` and `u:<unionId>`. Provider playlist
replacement deletes disappeared playlists' tracks and owner metadata in the same transaction.
A known empty track cache is a cache hit. Present caches older than six hours return immediately
and revalidate in the background; forced refresh errors propagate to the caller. Full refresh
isolates individual playlist failures and limits concurrent track fetches to four.

All secure stores load once on IO. Connection mutations are mutex-serialized and committed
before publishing their new state. Player settings publish immediately and persist on IO.
DJ settings retain legacy keys; `dj_voice_gain` is intentionally ignored.

HTTP calls use cancellable OkHttp enqueue. Retries close prior responses before coroutine delay:
429 for all methods, 5xx for GET/HEAD only, five retries maximum. Spotify catalog calls share
the token provider and perform one forced-refresh retry after a 401. Jellyfin resolves `/Users`
once per server/token pair and persists the selected id without startup network traffic.

## Verification

```sh
flock /tmp/ap-emulator.lock env ANDROID_SERIAL=emulator-5554 ./gradlew --console=plain -q \
  :core:data:testDebugUnitTest :core:data:connectedDebugAndroidTest
```

Instrumentation uses the library test package, never the installed application database.
