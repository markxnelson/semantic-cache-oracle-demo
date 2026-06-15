#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ ! -f reports/generated/validation-events.csv ]]; then
  ./scripts/run-validation.sh
fi

cp reports/generated/validation-events.csv reports/generated/benchmark-lite-events.csv
python3 - <<'PY'
import csv
from collections import Counter, defaultdict
from pathlib import Path

events_path = Path("reports/generated/benchmark-lite-events.csv")
summary_path = Path("reports/generated/benchmark-lite-summary.md")
rows = list(csv.DictReader(events_path.open()))

def percentile(values, pct):
    ordered = sorted(values)
    if not ordered:
        return ""
    index = min(len(ordered) - 1, round((pct / 100) * (len(ordered) - 1)))
    return ordered[index]

def latency(row):
    return int(row["latency_ms"])

by_route = defaultdict(list)
by_decision = Counter()
provider_calls = 0
for row in rows:
    by_route[row["route"]].append(latency(row))
    by_decision[row["decision"]] += 1
    provider_calls += int(row["provider_calls"])

avoided = sum(1 for row in rows if row["decision"] in {"exact-hit", "semantic-hit"} and int(row["provider_calls"]) == 0)
hit_count = by_decision["exact-hit"] + by_decision["semantic-hit"]
hit_rate = hit_count / len(rows) if rows else 0

lines = [
    "# Benchmark Lite Summary",
    "",
    "This lite report reuses the deterministic validation workload to confirm measurement wiring. It is not a production performance benchmark.",
    "",
    "## Workload",
    "",
    f"- Scenarios: `{len(rows)}`",
    "- Provider mode: deterministic mock",
    "- Embedding mode: deterministic fixture vectors",
    "- Units: milliseconds for latency columns",
    "",
    "## Decision Counts",
    "",
    "| Decision | Count |",
    "| --- | ---: |",
]
for decision, count in sorted(by_decision.items()):
    lines.append(f"| `{decision}` | {count} |")

lines.extend([
    "",
    "## Provider-Call Accounting",
    "",
    f"- Provider calls made: `{provider_calls}`",
    f"- Provider calls avoided by approved reuse: `{avoided}`",
    f"- Cache hit rate for approved exact or semantic reuse: `{hit_rate:.2%}`",
    "",
    "## Latency by Route",
    "",
    "| Route | Samples | p50 latency ms | p95 latency ms |",
    "| --- | ---: | ---: | ---: |",
])
for route, values in sorted(by_route.items()):
    lines.append(f"| `{route}` | {len(values)} | {percentile(values, 50)} | {percentile(values, 95)} |")

lines.extend([
    "",
    "## Modes Represented",
    "",
    "- no reusable cache entry on the seed miss",
    "- exact cache hit",
    "- semantic cache hit",
    "- semantic near miss",
    "- scoped rejection through tenant/model mismatch",
    "- source-fingerprint mismatch",
    "- expired-entry rejection",
    "- True Cache read-route configuration",
    "",
    "Use this file as Article 03 input only after a clean Docker Compose run has produced the validation CSV on the target machine.",
])

summary_path.write_text("\n".join(lines) + "\n")
PY

echo "Wrote reports/generated/benchmark-lite-events.csv"
echo "Wrote reports/generated/benchmark-lite-summary.md"
