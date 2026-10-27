# bot-manager

Creates, lists, trashes and removes the sandboxed Lab bots and forwards their chats. Runs inside the `hermes` Incus
container (see `docs/CONTAINER-BOTS-PLAN.md`). Python standard library only.

A bot is a Hermes profile `b<10 hex>` (system prompt in its `SOUL.md`, its own `API_SERVER_KEY`) whose tools run in a
Docker sandbox (`terminal.backend: docker`, 1 CPU, 1 GB, 5 GB disk, stopped 5 minutes after the last use). The phone only
holds the manager key; the per-bot keys never leave the server.

## API (all under `/m/v1`, Bearer = manager key, except `health`)

| | |
|---|---|
| `GET /health` | `{"ok":true}` |
| `GET /bots` | `{"bots":[{id,name,role,color,systemPrompt,createdAt,sandbox:"running"\|"idle"}],"trash":[...],"limit":5}` |
| `POST /bots` | `{name, role?, systemPrompt?, color?}` -> 201; 409 `limit_reached` at the limit |
| `PATCH /bots/{id}` | any of name, role, systemPrompt, color |
| `DELETE /bots/{id}` | active bot -> moved to the trash (sandbox removed, nothing else lost) |
| `POST /bots/{id}/restore` | trash -> active (409 at the limit) |
| `DELETE /bots/{id}?purge=1` | trashed bot -> removed for good (409 `not_in_trash` for an active one) |
| `POST /bots/{id}/chat/completions` | OpenAI-style, streamed (SSE) or not; only `messages`, `stream`, `temperature`, `max_tokens` are forwarded, client `system` messages are dropped |

Limits: `MAX_BOTS` (5) active bots, `MAX_TRASH` (20) trashed ones, 10 MB request body. Trashing needs no gateway reload (Hermes
picks profiles up and drops them live; do **not** use `systemctl reload hermes-gateway`, it restarts it).

## Janitor (every 5 minutes and at start)
Drops entries whose data is gone, discards creations that never finished, removes sandboxes of bots that are not active, and
**moves unknown `b...` profiles to the trash** (never deletes what it does not recognise).

## Install (in the container)
```
incus exec hermes -- mkdir -p /opt/bot-manager
tar -cf - bot_manager.py bot-manager.service setup.sh integration-check.py | incus exec hermes -- tar -C /opt/bot-manager -xf -
incus exec hermes -- bash /opt/bot-manager/setup.sh
```
`setup.sh` makes `bots.slice` (4 GB for all sandboxes) and Docker's `cgroup-parent`, the sandbox firewall (sandboxes reach the
internet and the Incus bridge's DNS, nothing private: 10/8, 192.168/16, 100.64/10, 169.254/16, and none of the container's own
services), the `bot-template` profile (docker backend, `terminal` + `file` tools on for `api_server`, web search on), and the
service. Config is `/etc/bot-manager.env` (`MANAGER_KEY`, `MAX_BOTS`, ...).

On the host: an Incus proxy device (`incus config device add hermes bot-manager proxy listen=tcp:127.0.0.1:8650
connect=tcp:127.0.0.1:8650`) and the `/m/v1/...` location in `deploy/nginx/bot`.

## Tests
`python3 -m unittest -v` here (fake Hermes/Docker backend). On the server, the real thing end to end:
`incus exec hermes -- su - jackson-hao -c 'python3 /opt/bot-manager/integration-check.py'` (creates a bot, runs commands in
its sandbox, proves the home network is unreachable from it, then trashes, restores and purges it).
