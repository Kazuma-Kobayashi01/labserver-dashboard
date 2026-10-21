#!/usr/bin/env python3
"""Export Hermes token usage (per day, profile, provider, model) for the dashboard.

Reads every profile's state.db read-only and writes one JSON file atomically. Values are merged with
the previous export by taking the maximum, so pruning sessions in Hermes never lowers the totals.
"""
import glob, json, os, sqlite3, sys, tempfile, time
from datetime import datetime

HOME = os.path.expanduser("~")
OUT = os.environ.get("HERMES_USAGE_FILE", "/var/lib/hermes-usage-export/usage.json")
FIELDS = ("calls", "input", "output", "cacheRead", "reasoning", "costUsd")

def profiles():
    yield "default", os.path.join(HOME, ".hermes", "state.db")
    for p in sorted(glob.glob(os.path.join(HOME, ".hermes", "profiles", "*", "state.db"))):
        yield os.path.basename(os.path.dirname(p)), p

QUERY = """
SELECT date(last_seen, 'unixepoch', 'localtime') AS day,
       coalesce(billing_provider, '') AS provider, coalesce(model, '') AS model,
       sum(coalesce(api_call_count, 0)), sum(coalesce(input_tokens, 0)), sum(coalesce(output_tokens, 0)),
       sum(coalesce(cache_read_tokens, 0)), sum(coalesce(reasoning_tokens, 0)),
       sum(CASE WHEN coalesce(actual_cost_usd, 0) > 0 THEN actual_cost_usd ELSE coalesce(estimated_cost_usd, 0) END)
FROM session_model_usage
WHERE last_seen IS NOT NULL
GROUP BY 1, 2, 3
"""

def current_rows():
    rows = {}
    for name, db in profiles():
        if not os.path.exists(db):
            continue
        try:
            con = sqlite3.connect(f"file:{db}?mode=ro", uri=True, timeout=5)
            for day, prov, model, calls, i, o, cr, rs, cost in con.execute(QUERY):
                rows[(day, name, prov, model)] = dict(zip(FIELDS, (calls, i, o, cr, rs, round(cost or 0, 6))))
            con.close()
        except sqlite3.Error as e:
            print(f"skip {name}: {e}", file=sys.stderr)
    return rows

def previous_rows():
    try:
        with open(OUT, encoding="utf-8") as f:
            d = json.load(f)
        return {(r["day"], r["profile"], r["provider"], r["model"]): {k: r.get(k, 0) for k in FIELDS} for r in d.get("rows", [])}
    except (OSError, ValueError, KeyError):
        return {}

def main():
    merged = previous_rows()
    for key, vals in current_rows().items():
        old = merged.get(key, {})
        merged[key] = {k: max(vals[k], old.get(k, 0)) for k in FIELDS}
    rows = [dict(day=k[0], profile=k[1], provider=k[2], model=k[3], **v) for k, v in sorted(merged.items())]
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(OUT), prefix=".usage-")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump({"generatedAt": int(time.time()), "rows": rows}, f, ensure_ascii=False)
    os.chmod(tmp, 0o644)
    os.replace(tmp, OUT)

if __name__ == "__main__":
    main()
