# LabLink for Android: design notes

What the app is: **an AI companion first, a home-lab remote second.** People open it to talk to a bot; the panels and
the tunnel are the reason the bot (and the panels) are reachable at all. The layout follows that order.

## What we borrowed, and from whom

| Product | What they do | What we took |
|---|---|---|
| Gemini, ChatGPT, Claude (Android) | Chat is the home screen; no bottom bar; a drawer holds history and settings; the model/bot name with ▾ in the top bar switches it | Chat as home, drawer navigation, bot switcher in the title |
| Gemini, Claude | The assistant's reply is plain full-width text (not a bubble); only the user's message sits in a soft bubble | Same: replies read like a page, the user bubble is tonal |
| Grok, Gemini | The composer is one big rounded container, tools inside it, a round send button | One 28 dp container, round send that morphs into a stop square |
| Grok | Empty screen = one big question, centered | Hero empty state: the bot's orb, its greeting, 2×2 suggestion cards |
| Character.AI, GPTs | Bots are visual: big avatar cards; creating one is its own full screen with a live preview | Bot editor with a live orb, colour swatches, template cards |
| Tailscale | Connection is one switch at the top; devices are a simple list; settings are grouped rows | Connection card with a switch (drawer + Home lab), grouped settings |
| Material 3 Expressive | Shape variety, emphasized type, contained content, spring motion, one or two "hero moments" | Big emphasized headlines, mixed shapes for panel tiles, springs, hero = the orb |

## The logo

A small planet-bot: a flat gradient orb with two slanted pill eyes (the "orb with eyes" idea, as in Grok's mascot) and an
orbit ring with a satellite dot (the *link* in LabLink: a device circling home). It reads at 24 dp, works in one colour
for themed icons, and every bot gets its own orb colour while the orbit stays amber. Hero moments: the satellite travels
along the orbit and the eyes blink while the bot is thinking.

## Rules of thumb used in the code

* Corners: 28 dp for containers, 50 % for buttons and the orb, mixed shapes only for decorative tiles.
* Colour: dynamic colour for surfaces (Android 12+), the bot's own colour for its orb, amber for the orbit.
* Type: `headlineMedium` / `titleLarge` with heavier weight for hero text; body text stays `bodyLarge`.
* Motion: springs (`spring(dampingRatio = 0.7f)`) for size/shape changes; nothing loops unless the bot is busy.
