#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ ! -f .env ]]; then
  cp .env.example .env
fi

set -a
source .env
set +a

export BENCHMARK_REQUESTS="${BENCHMARK_REQUESTS:-1000}"
export BENCHMARK_WORKLOAD_FAMILIES="${BENCHMARK_WORKLOAD_FAMILIES:-50}"
export BENCHMARK_CONCURRENCY="${BENCHMARK_CONCURRENCY:-1}"
export BENCHMARK_PROVIDER_MODE="${BENCHMARK_PROVIDER_MODE:-openai}"
export BENCHMARK_PROVIDER_LATENCY_MS="${BENCHMARK_PROVIDER_LATENCY_MS:-10}"
export BENCHMARK_PREWARM_PAUSE_MS="${BENCHMARK_PREWARM_PAUSE_MS:-1500}"
export BENCHMARK_SEED="${BENCHMARK_SEED:-260612}"

echo "== Full semantic cache benchmark wrapper =="
echo "This benchmark compares no-cache, exact-cache, semantic-cache-primary, semantic-cache-true-cache, and semantic-cache-write-through modes."
echo "Requests per mode: ${BENCHMARK_REQUESTS}"
echo "Workload families: ${BENCHMARK_WORKLOAD_FAMILIES}"
echo "Concurrency: ${BENCHMARK_CONCURRENCY}"
echo "Provider mode: ${BENCHMARK_PROVIDER_MODE}"
if [[ "${BENCHMARK_PROVIDER_MODE}" == "deterministic" ]]; then
  echo "Provider latency simulation: ${BENCHMARK_PROVIDER_LATENCY_MS} ms"
else
  echo "OpenAI model: ${OPENAI_CHAT_MODEL:-gpt-4o-mini}"
fi
echo "Prewarm visibility pause: ${BENCHMARK_PREWARM_PAUSE_MS} ms"
echo

echo "Checking primary database and registered True Cache application login readiness..."
./scripts/wait-for-oracle.sh

echo "Building the Spring Boot benchmark app..."
./mvnw -q -pl app -am package -DskipTests

echo "Running full benchmark..."
SEM_CACHE_RUN_MODE=benchmark-full env -u DEBUG java -jar app/target/semantic-cache-oracle-app-0.1.0-SNAPSHOT.jar

echo "Generating benchmark charts with matplotlib..."
MPLCONFIGDIR="${MPLCONFIGDIR:-/tmp/semantic-cache-matplotlib}" python3 - <<'PY'
import csv
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

generated = Path("reports/generated")
latency_path = generated / "benchmark-full-latency.csv"
cost_path = generated / "benchmark-full-cost.csv"
events_path = generated / "benchmark-full-events.csv"

latency_rows = list(csv.DictReader(latency_path.open()))
cost_rows = list(csv.DictReader(cost_path.open()))
event_rows = list(csv.DictReader(events_path.open()))

def save_bar(path, title, ylabel, labels, values, color, value_format=None):
    fig, ax = plt.subplots(figsize=(10, 5.5))
    ax.bar(labels, values, color=color)
    ax.set_title(title)
    ax.set_ylabel(ylabel)
    ax.tick_params(axis="x", rotation=25)
    ax.grid(axis="y", alpha=0.25)
    for index, value in enumerate(values):
        label = value_format(value) if value_format else str(value)
        ax.text(index, value, label, ha="center", va="bottom", fontsize=8)
    fig.tight_layout()
    fig.savefig(generated / path, dpi=160)
    plt.close(fig)

def save_stacked_bar(path, title, ylabel, labels, lower_values, upper_values, lower_label, upper_label):
    fig, ax = plt.subplots(figsize=(10, 5.5))
    ax.bar(labels, lower_values, color="#76b7b2", label=lower_label)
    ax.bar(labels, upper_values, bottom=lower_values, color="#edc948", label=upper_label)
    ax.set_title(title)
    ax.set_ylabel(ylabel)
    ax.tick_params(axis="x", rotation=25)
    ax.grid(axis="y", alpha=0.25)
    ax.legend(loc="upper right")
    for index, total in enumerate([lower_values[i] + upper_values[i] for i in range(len(labels))]):
        ax.text(index, total, f"{total:.0f}", ha="center", va="bottom", fontsize=8)
    fig.tight_layout()
    fig.savefig(generated / path, dpi=160)
    plt.close(fig)

def percentile(values, pct):
    if not values:
        return 0
    values = sorted(values)
    index = round((pct / 100) * (len(values) - 1))
    return values[index]

modes = [row["mode"] for row in latency_rows]
save_bar(
    "benchmark-full-wall-clock.png",
    "Wall-clock time by benchmark mode (lower is better)",
    "Wall-clock milliseconds",
    modes,
    [int(row["wall_clock_ms"]) for row in latency_rows],
    "#4e79a7",
    lambda value: f"{value:.0f}",
)
save_bar(
    "benchmark-full-requests-per-second.png",
    "Throughput by benchmark mode (higher is better)",
    "Requests per second",
    modes,
    [float(row["requests_per_second"]) for row in latency_rows],
    "#59a14f",
    lambda value: f"{value:.2f}",
)

events_by_mode = {
    mode: [row for row in event_rows if row["mode"] == mode]
    for mode in modes
}
avg_non_provider_latency = [
    sum(int(row["non_provider_latency_ms"]) for row in events_by_mode[mode]) / len(events_by_mode[mode])
    for mode in modes
]
avg_provider_latency = [
    sum(int(row["provider_latency_ms"]) for row in events_by_mode[mode]) / len(events_by_mode[mode])
    for mode in modes
]
save_stacked_bar(
    "benchmark-full-latency-components.png",
    "Average request latency components by mode (lower is better)",
    "Average milliseconds per request",
    modes,
    avg_non_provider_latency,
    avg_provider_latency,
    "App, database, and cache",
    "LLM provider",
)

cache_hit_modes = []
cache_hit_avg = []
cache_hit_p95 = []
for mode in modes:
    hit_rows = [
        row for row in events_by_mode[mode]
        if row["provider_calls"] == "0" and row["decision"] in {"exact-hit", "semantic-hit"}
    ]
    if hit_rows:
        values = [int(row["non_provider_latency_ms"]) for row in hit_rows]
        cache_hit_modes.append(mode)
        cache_hit_avg.append(sum(values) / len(values))
        cache_hit_p95.append(percentile(values, 95))

fig, ax = plt.subplots(figsize=(10, 5.5))
x = list(range(len(cache_hit_modes)))
width = 0.38
ax.bar([value - width / 2 for value in x], cache_hit_avg, width=width, color="#76b7b2", label="Average")
ax.bar([value + width / 2 for value in x], cache_hit_p95, width=width, color="#4e79a7", label="p95")
ax.set_title("Cache-hit lookup latency, provider calls excluded (lower is better)")
ax.set_ylabel("Milliseconds")
ax.set_xticks(x)
ax.set_xticklabels(cache_hit_modes, rotation=25, ha="right")
ax.grid(axis="y", alpha=0.25)
ax.legend(loc="upper right")
for index, value in enumerate(cache_hit_avg):
    ax.text(index - width / 2, value, f"{value:.1f}", ha="center", va="bottom", fontsize=8)
for index, value in enumerate(cache_hit_p95):
    ax.text(index + width / 2, value, f"{value:.0f}", ha="center", va="bottom", fontsize=8)
fig.tight_layout()
fig.savefig(generated / "benchmark-full-cache-hit-lookup-latency.png", dpi=160)
plt.close(fig)

cost_by_mode = {row["mode"]: row for row in cost_rows}
save_bar(
    "benchmark-full-provider-calls.png",
    "Provider calls made by benchmark mode (lower is better)",
    "Provider calls",
    modes,
    [int(cost_by_mode[mode]["provider_calls_made"]) for mode in modes],
    "#f28e2b",
    lambda value: f"{value:.0f}",
)
save_bar(
    "benchmark-full-cost.png",
    "Estimated provider cost by benchmark mode (lower is better)",
    "Estimated USD",
    modes,
    [float(cost_by_mode[mode]["estimated_cost_usd"]) for mode in modes],
    "#e15759",
    lambda value: f"${value:.4f}",
)

print("Generated charts:")
print("- reports/generated/benchmark-full-wall-clock.png")
print("- reports/generated/benchmark-full-requests-per-second.png")
print("- reports/generated/benchmark-full-latency-components.png")
print("- reports/generated/benchmark-full-cache-hit-lookup-latency.png")
print("- reports/generated/benchmark-full-provider-calls.png")
print("- reports/generated/benchmark-full-cost.png")
PY

echo "Full benchmark reports:"
ls -1 \
  reports/generated/benchmark-full-config.json \
  reports/generated/benchmark-full-events.csv \
  reports/generated/benchmark-full-latency.csv \
  reports/generated/benchmark-full-cost.csv \
  reports/generated/benchmark-full-summary.json \
  reports/generated/benchmark-full-summary.md \
  reports/generated/benchmark-full-wall-clock.png \
  reports/generated/benchmark-full-requests-per-second.png \
  reports/generated/benchmark-full-latency-components.png \
  reports/generated/benchmark-full-cache-hit-lookup-latency.png \
  reports/generated/benchmark-full-provider-calls.png \
  reports/generated/benchmark-full-cost.png
