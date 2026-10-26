# Lab bot: exposing Hermes to the Android app

The Android app's **Lab bot** tab talks to Hermes through its built-in OpenAI-compatible API server, using a dedicated,
restricted profile called `labbot`. Nothing else of Hermes is exposed.

```
app ─ tunnel ─ nginx (bot.labserver.internal :80) ─ 127.0.0.1:8642 ─ host gateway ─ profile "labbot"
```

## 1. A restricted profile

```bash
hermes profile create labbot --clone-from default --no-alias --description "Lab bot (restricted tools)"
# no shell, no files, no browser, no delegation, no cron: chat, web search, vision, notes, planning only
hermes -p labbot tools disable terminal file code_execution browser delegation cronjob computer_use \
    connections image_gen tts skills --platform api_server
$EDITOR ~/.hermes/profiles/labbot/SOUL.md      # persona (friendly, short answers, replies in the user's language)
```

The profile has **its own API key**; a named profile never falls back to the owner's key.

## 2. Turn the API server on

Add to `~/.hermes/.env` (the default profile's; the host gateway serves every profile) and to
`~/.hermes/profiles/labbot/.env`:

```bash
# ~/.hermes/.env
API_SERVER_ENABLED=true
API_SERVER_HOST=127.0.0.1
API_SERVER_PORT=8642
API_SERVER_KEY=<random, 16+ chars>            # authorises the default profile on loopback only; never given out

# ~/.hermes/profiles/labbot/.env
API_SERVER_ENABLED=true
API_SERVER_MODEL_NAME=lab-bot
API_SERVER_KEY=<random, 16+ chars>            # THIS is the key that goes into the app
```

Generate keys with `openssl rand -hex 24`. Then `hermes gateway restart` (the multiplexed host gateway also serves your
other profiles and bots, so they reconnect for a moment).

The labbot endpoints are `http://127.0.0.1:8642/p/labbot/v1/…`. Check the isolation:

```bash
curl -H "Authorization: Bearer $LABBOT_KEY"  http://127.0.0.1:8642/p/labbot/v1/models   # 200
curl -H "Authorization: Bearer $DEFAULT_KEY" http://127.0.0.1:8642/p/labbot/v1/models   # 401
curl -H "Authorization: Bearer $LABBOT_KEY"  http://127.0.0.1:8642/v1/models            # 401
```

## 3. nginx: only the chat endpoints

`deploy/nginx/bot` + `deploy/nginx/labbot-proxy.conf` publish just `chat/completions`, `models` and `capabilities` of the
`labbot` profile under `bot.labserver.internal`; every other path (other profiles, sessions, cron, uploads…) is a 404.

```bash
sudo install -m 644 deploy/nginx/labbot-proxy.conf /etc/nginx/snippets/labbot-proxy.conf
sudo install -m 644 deploy/nginx/bot /etc/nginx/sites-available/bot
sudo ln -s /etc/nginx/sites-available/bot /etc/nginx/sites-enabled/bot
sudo nginx -t && sudo systemctl reload nginx
```

## Why it is built this way

The agent behind Hermes can run tools. The key for this endpoint is therefore as sensitive as a shell on the server, which
is why the profile has no terminal / file / code / browser tools and the proxy exposes three read-or-chat routes only.
If you ever give `labbot` more tools, treat the key accordingly. Rotate it by editing the `.env` and restarting the
gateway, then paste the new key into the app (**Link → Lab bot**).
