---
name: labserver-feature
description: Use whenever the user asks to add, create, build, extend or change a feature, metric, page, panel, link, integration or setting in the Labserver project (Go backend, Vue web dashboard, or the LabLink macOS app). Enforces that every feature ships in BOTH the web dashboard and the macOS app, fed by one read-only backend endpoint, with docs updated, verified and (on request) deployed.
---

# Labserver: every feature lands in the web dashboard AND the macOS app

## The rule

A new user-facing feature is not done until it is visible in **both** clients, backed by **one** read-only backend
endpoint:

| Layer | Where | Role |
|---|---|---|
| Backend | `backend/` (Go + Gin) | Single source of truth. `GET /api/v1/<name>` returning the standard envelope. |
| Web | `frontend/` (Vue 3 + Vuetify + TS) | Page or component, fed by the same endpoint. |
| App | `mac/LabLink/` (SwiftUI) | Row, group or stat, fed by the same endpoint. |

Do not ship one side only. If a feature genuinely cannot make sense in one client (for example a wide chart in a 300 px
panel), say so, propose the closest equivalent (a summary row that opens the web page), and get the user's OK first.

Neither client changes by itself when the backend changes: the web page is a static build and the app decodes only the
fields it declares. **Both must be edited for each feature.**

## Hard constraints (never break these)

- Backend is **read-only**: `GET` only, never executes shell commands, never writes to Incus or the system.
- Listens on `127.0.0.1` only; runs as an unprivileged user (see `deploy/systemd/`). No root/sudo for the API.
- No secrets, tokens, certificates or keys in any response, in the browser, or in the app. Secrets stay server-side
  (`/etc/labserver-dashboard/`).
- Do not open new LAN or public ports; new sites go behind Nginx `:80` by host name.
- Back up any config before changing it; `nginx -t` then **reload** (never restart); no deleting instances or data.
- No personal values in the repo (IPs, usernames, keys): use `Config.swift`, `env.example` or placeholders.
- Do not install persistent or privileged things on the user's machines without asking first.

## Workflow

### 0. Design first (say it to the user in a few lines)

Decide and state: what the feature shows or does, the endpoint name and JSON shape, the polling interval, where it
appears on the web (page / card / nav link), where it appears in the app (new group, row, or header stat), and the
strings in English / 中文 / 日本語. Ask only if something is genuinely ambiguous.

### 1. Backend

- New logic in `backend/internal/<name>/`; route in `backend/internal/api/api.go` next to the others
  (`v1.GET("/<name>", ...)`). Respond with the existing helpers so the shape stays `{ "ok": true, "data": ... }`.
- Optional integrations: keep the client `nil` when not configured and return a clear "not configured" result instead of
  failing the whole API. Wire it in `backend/cmd/server/main.go` with an env var (follow `MIHOMO_URL` / `DEEPSEEK_KEY_FILE`).
- Add the env var to `deploy/env.example`. If it is a service, add it to `deploy/services.json`.
- Use timeouts and small caches for anything that calls out. Keep payloads small (the app polls every few seconds).

### 2. Web (`frontend/src/`)

- Types and call in `services/api.ts`: add the `interface` and `api.<name> = () => get<T>('/<name>')`.
- Fetch with `usePolling(api.<name>, <ms>)` (`composables/usePolling.ts`); show `ErrorState` on failure.
- Reuse components (`MetricTile`, `Sparkline`, `AreaChart`, `BarChart`, `StatusDot`, ...) and theme tokens
  (`theme/tokens.css`, `theme/palettes.ts`). No hard-coded colors: it must work in all three styles, light and dark.
- New page: add the route in `router/index.ts` and the nav link in `App.vue` (`links`). New external link: the Apps
  dropdown list in `App.vue`.
- `cd frontend && pnpm build` must pass (it runs `vue-tsc`).

### 3. macOS app (`mac/LabLink/Sources/LabLink/`)

- `Model.swift`: add a `Decodable` struct with only the fields you need, a `@Published` property on `TunnelModel`, and
  load it in `refresh()` with `await Self.fetch("<name>", host: host)` (only when `base != nil`; it sends
  `Host: Config.domain` already).
- `ContentView.swift`: render with the existing building blocks (`Group_`, `ItemRow`, `InstanceRow`, header `Stat`).
  Colors come from `Pal` (light and dark), fonts from `.ui(size:)` (never `.system(size:)`). The window is resizable
  from 300 to 440 pt wide, so nothing may assume a fixed width.
- **Every user-visible string goes through `L10n.t("key")`**, with an `(English, 中文, 日本語)` entry in the table in
  `Settings.swift`. No hard-coded Chinese or English in views.
- Network constants, domains and user names live in `Config.swift`, not inline. New toggles go in `AppSettings` and
  `SettingsView`.
- Panel links: the app's panel list is the hard-coded `panels` array in `ContentView.swift`, and the web has its own
  lists in `App.vue`. Until a backend `/api/v1/apps` endpoint exists (check for it first), a new panel must be added in
  both places and in `deploy/services.json`.
- Build with `cd mac/LabLink && ./scripts/build.sh`.

### 4. Docs and deploy files

- Update **both** `README.md` and `README.zh-CN.md` (feature table, env vars, setup steps if they changed).
- Add or update `deploy/` files (nginx site, `env.example`, `services.json`, firewall) as needed.
- Keep secrets and personal values out; use placeholders.

### 5. Verify (do not claim done without this)

- Backend: `go vet ./... && go build ./cmd/server` (use the server if Go is not installed locally), then
  `curl` the endpoint on `127.0.0.1:18080` and through Nginx.
- Web: `pnpm build`; open the page and check light and dark.
- App: build, launch, screenshot (`screencapture -l <windowid>`) in light, dark and each language; check it at the
  narrowest and widest window width.
- Compare the numbers in the web page and the app: they must match the same endpoint.
- Say plainly what you could not verify.

### 6. Deploy (only when the user says so; list what you will touch first)

- Backend: build on the server, install to `/opt/labserver-dashboard/bin/`, `systemctl restart labserver-dashboard`.
- Web: sync `frontend/dist/` to `/opt/labserver-dashboard/web/`.
- Nginx/firewall changes: back up first, `nginx -t && systemctl reload nginx`.
- App: quit the running app, copy `build/LabLink.app` to `/Applications/`, relaunch.

### 7. Commit

One commit per feature with a clear message. End it with the attribution line the session instructs. Check
`git status` for stray secrets or build output before committing.

## Final report to the user

Short and concrete: the endpoint, what was added on the web, what was added in the app, docs touched, what was verified
(and how), what was not, and whether it is deployed.
