# Bots in sandboxes, Hermes in Incus, remote builds: plan

Status: plan only, nothing installed. Hardware facts (told by the user, Docker/iptables output seen): MateBook, i7-13700H
(20 threads), 15 GiB RAM (2.5 used), 24 GiB swap, plenty of disk. Host Docker 29.1.3 exists but is empty (only a
`hello-world`); `iptables FORWARD` policy is ACCEPT. Incus runs `forgejo`, `nextcloud`, `wg-gw` (+ two stopped instances).

## Target layout

```
MateBook host
├─ Incus: wg-gw, forgejo, nextcloud            (unchanged)
└─ Incus system container "hermes"             (new; security.nesting=true, one memory/CPU cap for everything inside)
   ├─ Hermes (host gateway, profiles)          总管 = default profile, other bots = more profiles
   ├─ Docker (inside the container)
   │   ├─ bot sandboxes   (Hermes `terminal.backend: docker`)   -> bots.slice
   │   └─ build toolchains (images) + SDK/cache volumes          -> dev.slice
   └─ bot-manager
```

Engine rule: single-purpose apps and sandboxes run in Docker; long-lived multi-part services and anything that is "a
small machine" run in Incus. Docker lives *inside* the `hermes` container, not on the host (the empty host Docker can
be removed once this works). No Incus OCI containers, so no Incus upgrade is needed.

## Bots

| | 总管 (default bot) | Other bots |
|---|---|---|
| Count | 1, cannot be deleted | at most **5** |
| Where it works | the `hermes` container itself (local backend) | a Docker sandbox, never the Hermes environment |
| Commands | yes | yes, **inside the sandbox only** (also covers compiling) |
| Files | everything inside the `hermes` container | its own sandbox workspace; documents and images the user sends it |
| Model key | in Hermes, never in a sandbox | same |
| Delete | never | sandbox + volume + profile + registry entry removed together |

- A bot = a Hermes profile (system prompt, colour, tool config) + a sandbox. Tools stay restricted by profile config.
- Moving Hermes into its own Incus container is what keeps 总管 from reading the host: its "everything" is the
  container's everything. If 总管 later needs to reach the host or other Incus instances, give it a narrow, explicit
  path (a mounted folder, or a restricted Incus client certificate), never blanket host access.
- **Must test first** (docs only describe one persistent container per Hermes process): (1) do two profiles get separate
  sandboxes, (2) do file-read tools go through the sandbox or run on the Hermes side, (3) exact config keys on
  Hermes 0.22.2. If (1) fails: one Hermes gateway process per bot, or a custom terminal-environment plugin.

## bot-manager (inside `hermes`)

- `POST /bots` (refuses at 5), `DELETE /bots/{id}` (removes profile, sandbox, volume, registry entry), list, and chat
  proxy; a janitor reconciles sandboxes vs registry every few minutes so nothing is left after a crash.
- Sandboxes stop when idle and wake on the next message; at most 2–3 run at once.
- Sandbox hardening: no privileged mode, no host mounts, no published ports, own network without access to the home
  LAN, cap-drop ALL, memory / CPU / pids limits.
- nginx keeps exposing only the manager's routes (everything else 404). The Docker socket is only for the manager and
  Hermes, never over the network.

## 总管 guards

A full-permission agent reachable from the phone is the risk. Own API key, reachable only through the tunnel, Hermes'
dangerous-command approval kept on (the app shows Approve / Deny), key revocable from the Mac app. The `hermes` container
being separate also bounds the damage.

## Remote build

Goal: write code on the Mac, build on the MateBook, keep the large output off the Mac.

- Toolchains are versioned Docker images (`dev-android:35`, `dev-embedded`, `dev-rust`, `dev-web`): update = pull a new
  tag, remove = delete the image. SDKs and caches are named volumes (`android-sdk`, `gradle-cache`, `cargo-registry`,
  `sccache`, `npm-cache`), each removable alone.
- Projects live in one host folder, mounted by Incus into `hermes` and by Docker into the toolchain container
  (needs `shift=true` / idmap tested for ownership).
- `rbuild` on the Mac: syncs sources (output dirs excluded), runs in the right toolchain, streams output, brings back
  only the files asked for (`.apk`, `.elf`, `.bin`).
- Flashing and debugging stay on the Mac (debugger on its USB; Incus USB passthrough possible later). macOS / Xcode
  projects stay on the Mac. No Android emulator.
- Bots can also compile inside their sandbox, or submit a job to a toolchain through the manager.

## Resources

Budget for 15 GiB: host + Incus services + Hermes about 3 GiB, `bots.slice` 4 GiB, `dev.slice` 8 GiB (swap for the build
group limited to about 6 GiB so it cannot drag the machine). Incus limits cap the whole `hermes` container; inside it,
Docker's `cgroup-parent` puts sandboxes and builds into the two slices, so a heavy build cannot starve a chat. One
heavy build at a time; Gradle `-Xmx` limited; job time limit.

## Order of work

1. Persist `net.ipv4.ip_forward=1`; reboot-test that `wg-gw` still works with Docker present.
2. Create the Incus container `hermes` (nesting on, caps set, storage checked: `overlay2` inside ZFS/Btrfs can fail, use
   a dedicated ext4-backed volume if needed); install Docker inside; make the slices.
3. Move Hermes into it (profiles, `.env`, SOUL.md, nginx upstream); keep the old install until the new one is verified.
4. Run the three sandbox tests above with a throwaway profile.
5. bot-manager + tests (the 5 limit, cleanup after a crash, delete removes everything).
6. 总管 key, approval flow; Android: manager client, "x/5", delete wording, 总管 badge.
7. `dev-web` + `rbuild`, then `dev-embedded`, then `dev-android`.
8. Images and documents from the phone land in the bot's workspace (the earlier image/file plan).

## Decisions still open

- Web search for sandboxed bots (proposed: yes).
- Convert every existing light bot to profile + sandbox (proposed: yes, 5 max).
- Model key: shared in Hermes, or per bot (proposed: shared, since it never enters a sandbox).
- Whether 总管 gets a path to the host or other Incus instances (proposed: not at first).
