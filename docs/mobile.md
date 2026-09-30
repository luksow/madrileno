# Mobile

The **reference mobile app** — a separate repo, [`madrileno-dev/madrileno-mobile`](https://github.com/madrileno-dev/madrileno-mobile) — is the phone-shaped sibling of the [reference frontend](frontend.md): a React Native / Expo app built against this backend's **generated contract**, so the Scala router specs stay the single source of truth for a third consumer too.

Like the frontend, it is a **sibling repo, not a subdirectory**, and the backend stays unaware of it. The only coupling is the contract the backend already generates.

## The contract loop

The same loop as the frontend — see [`frontend.md`](frontend.md#the-contract-loop). `sbt testFull` writes the oRPC contract package to `target/baklava/orpc/src/`; the app's `pnpm run sync-contracts` vendors it into `src/contracts/` (committed, so the app builds without a backend checkout), and `pnpm run typecheck` fails at the call site that read a renamed field. The web and mobile apps share the same generated types, so one backend change surfaces in both at once.

## What the mobile app is

At a glance — details live in the mobile repo's own README:

- **Expo (SDK 57) + React Native + TypeScript (strict)**, file-based routing with **Expo Router** and typed routes. Android is built and tested on the emulator; iOS builds go through EAS.
- The **same API layer as the web**: oRPC client over the generated contract, typed RFC 9457 errors (see [`error-handling.md`](error-handling.md)), TanStack Query, react-hook-form + zod, Temporal at the wire boundary. `src/api/` is copied from the frontend and kept diffable.
- **Auth** against the dev login + JWT/refresh flow (see [`auth.md`](auth.md)), tokens in the platform keychain (`expo-secure-store`), with the same single-flight 401 refresh as the web.
- **UI** on NativeWind with [react-native-reusables](https://reactnativereusables.com) — shadcn's approach for React Native: vendored components, the same token palette as the frontend, light / dark / system theming.
- **Native concerns the web doesn't have**: deep links (a custom scheme, plus opt-in Universal / App Links), consent-gated **OTA updates** via EAS Update, and optional **OpenObserve RUM**.
- Tests: jest-expo + React Native Testing Library + MSW, and Maestro flows on an emulator in CI.

## Tracing across the boundary

With RUM enabled, the app sends a W3C `traceparent` on its API calls, so the backend's inbound span (see [`observability.md`](observability.md)) joins the app's trace: one trace runs from the screen view through the HTTP call to the Postgres queries. The app also tags its RUM session with the signed-in user's id — the same id the backend puts on its spans as `app.user.id` — so searching one id finds both a user's app sessions and their backend traces. Only the id is sent, never the email.

## Using it

Clone it next to this repo and follow its README. The backend needs no changes:

- **On the Android emulator** the app's default API base URL is `http://10.0.2.2:9000` — the emulator's alias for the host — so it reaches a locally running backend with no config. A physical device uses your machine's LAN IP.
- **CORS doesn't apply**: a native app isn't a browser, so `CORS_ALLOWED_ORIGINS` (see [`configuration.md`](configuration.md)) is irrelevant to it.
- **Cleartext HTTP** is allowed only for non-production builds pointed at an `http://` API; production builds need an `https://` backend.

## Starting a real project

The app ships the same "delete the demo" escape hatch as the backend's [`init-project`](scripts.md) and the frontend's:

```bash
node scripts/init-project.mjs my-project
```

It deletes the auction demo (every `mobile:auction-block-*` marker block, mirroring the backend's `scripts:auction-block-*` markers), renames the package — which drives the app name, URL scheme and bundle id — and leaves a runnable shell: login, the typed client, routing, settings and tests. Run it alongside the backend's own `init-project.scala`, then regenerate and resync the contract (`sbt testFull` → `pnpm run sync-contracts`).
