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
| `GET /bots` | `{"bots":[{id,name,role,color,systemPrompt,createdAt,sandbox:"running"\|"idle"}],"trash":[{...,trashedAt,expiresAt}],"limit":5,"trashLimit":20,"trashDays":7}` |
| `POST /bots` | `{name, role?, systemPrompt?, color?}` -> 201; 409 `limit_reached` at the limit |
| `PATCH /bots/{id}` | any of name, role, systemPrompt, color |
| `DELETE /bots/{id}` | active bot -> moved to the trash (sandbox removed, nothing else lost) |
| `POST /bots/{id}/restore` | trash -> active (409 at the limit) |
| `DELETE /bots/{id}?purge=1` | trashed bot -> removed for good (409 `not_in_trash` for an active one) |
| `POST /bots/{id}/chat/completions` | OpenAI-style, streamed (SSE) or not; only `messages`, `stream`, `temperature`, `max_tokens` are forwarded, client `system` messages are dropped |

Limits: `MAX_BOTS` (5) active bots, `MAX_TRASH` (20) trashed ones that are removed for good after `TRASH_DAYS` (7; 0 = never), 10 MB request body. Trashing needs no gateway reload (Hermes
picks profiles up and drops them live; do **not** use `systemctl reload hermes-gateway`, it restarts it).

## Janitor (every 5 minutes and at start)
Removes bots that stayed in the trash too long, drops entries whose data is gone, discards creations that never finished, removes sandboxes of bots that are not active, and
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

## Models and thinking levels

`GET /m/v1/models` serves the catalog in `~/.hermes/bot-manager/catalog.json`: providers (DeepSeek, ChatGPT), their models and the
thinking levels each accepts (`none low medium high xhigh max`). `probe_models.py` builds it from Hermes itself: the ChatGPT
account's live model list and Hermes' own per-model level rules (read by a helper module that is put into Hermes' source tree for the
moment of the call and removed again), the models DeepSeek lists for our key, and a one-request check per model through the
restricted `labbot` profile. Re-run it when accounts change:
`incus exec hermes -- su - jackson-hao -c 'python3 /opt/bot-manager/probe_models.py'`.

A bot may carry a default (`modelProvider`, `modelId`, `reasoning` on create/PATCH); a chat request may override it with the same three
fields. Both are checked against the catalog (400 `unknown_model` / `unknown_reasoning`) and sent to Hermes as an explicit `provider` +
`model` + `model_options.reasoning` (Hermes ignores a bare model name; a rejected model still answers HTTP 200 with
`finish_reason: "error"`). ChatGPT and DeepSeek logins live on the server only (`hermes auth`, `.env`).
