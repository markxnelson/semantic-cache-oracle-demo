# Semantic Cache with Oracle AI Database 26ai and Oracle True Cache

This demo shows a small, inspectable semantic-cache implementation with Oracle AI Database 26ai Free and Oracle True Cache.

The default provider mode is deterministic `mock`. It exercises cache decisions without calling OpenAI or OCI Generative AI. The cache implementation is kept in `oracle-semantic-cache/` so the Oracle-specific policy and SQL can be reviewed or reused separately from the sample app.

## What It Proves

- Oracle AI Database 26ai Free stores semantic-cache entries and events.
- `SEM_CACHE_ENTRY.PROMPT_EMBEDDING` uses Oracle's native `VECTOR` type.
- Exact prompt-hash lookups use the configured read route.
- Semantic lookups use `VECTOR_DISTANCE(prompt_embedding, TO_VECTOR(?), COSINE)`.
- Tenant, model, template, source, policy, status, and TTL predicates gate reuse.
- The validation workload includes source-fingerprint mismatch and expired-entry cases.
- Misses and near misses call the provider and write through the primary route.
- Events record decision, reason, route, distance, threshold, provider calls, and latency.
- Reports are generated as CSV, JSON, and Markdown.

## Run

```bash
cp .env.example .env
./scripts/start-databases.sh
./scripts/wait-for-oracle.sh
./scripts/run-validation.sh
./scripts/run-benchmark-lite.sh
```

`run-validation.sh` runs the readiness check before starting the Java application. If that check cannot see the `semcache_pdb_tc` True Cache service, rerun `./scripts/start-databases.sh` and then run validation again.

The expected reports are:

```text
reports/generated/validation-evidence.md
reports/generated/validation-events.csv
reports/generated/validation-summary.json
reports/generated/validation-summary.md
reports/generated/benchmark-lite-events.csv
reports/generated/benchmark-lite-summary.md
```

## Full Benchmark

`run-benchmark-lite.sh` is intentionally small. It reuses the eight validation scenarios to prove measurement wiring.

For a larger benchmark that compares cache modes, run:

```bash
BENCHMARK_REQUESTS=1000 \
BENCHMARK_WORKLOAD_FAMILIES=50 \
BENCHMARK_CONCURRENCY=1 \
BENCHMARK_PROVIDER_MODE=openai \
BENCHMARK_PREWARM_PAUSE_MS=1500 \
./scripts/run-benchmark-full.sh
```

The full benchmark uses real OpenAI calls by default. Set `OPENAI_API_KEY` before running it, and optionally set `OPENAI_CHAT_MODEL`, `OPENAI_INPUT_COST_PER_MILLION`, and `OPENAI_OUTPUT_COST_PER_MILLION` to match the model and pricing assumptions you want reported.

For a quick wiring smoke test that does not call OpenAI, run the same script with `BENCHMARK_PROVIDER_MODE=deterministic`. Do not use deterministic-mode latency charts as performance evidence.

The full benchmark compares:

- `no-cache`
- `exact-cache`
- `semantic-cache-primary`
- `semantic-cache-true-cache`
- `semantic-cache-write-through`

It writes CSV, JSON, Markdown, and matplotlib PNG charts under `reports/generated/`:

```text
reports/generated/benchmark-full-config.json
reports/generated/benchmark-full-events.csv
reports/generated/benchmark-full-latency.csv
reports/generated/benchmark-full-cost.csv
reports/generated/benchmark-full-summary.json
reports/generated/benchmark-full-summary.md
reports/generated/benchmark-full-wall-clock.png
reports/generated/benchmark-full-requests-per-second.png
reports/generated/benchmark-full-provider-calls.png
reports/generated/benchmark-full-cost.png
```

In OpenAI mode, token counts come from the OpenAI Responses API usage fields and cost values are calculated from those reported tokens and the configured per-million-token prices. Treat the cost as a benchmark estimate unless the configured prices match the model pricing you intend to use.

The default concurrency is `1` because the local Oracle True Cache Free container can reject bursts of concurrent listener connections on small developer machines. Raise `BENCHMARK_CONCURRENCY` deliberately when you want to study concurrency on a machine and container configuration sized for it.

Before each measured cache mode, the harness seeds one cache entry per workload family, waits for the configured prewarm pause, and then starts the measured request set. The prewarm count and workload-family count are written to `benchmark-full-config.json` and `benchmark-full-summary.md` because they are part of the test method.

## Routes

The app uses two explicit JDBC URLs:

- `PRIMARY_JDBC_URL` for inserts, events, schema reset, and validation writes.
- `TRUE_CACHE_JDBC_URL` for eligible exact and semantic lookup SQL.

For development you can temporarily point `TRUE_CACHE_JDBC_URL` at the primary database to debug the Java code. To validate the Oracle True Cache path, run with `TRUE_CACHE_JDBC_URL` pointed at the registered True Cache service.

## Provider Modes

The runnable validation path is `SEM_CACHE_PROVIDER=mock`.

Examples for later provider expansion:

```text
SEM_CACHE_PROVIDER=openai
OPENAI_API_KEY=...
OPENAI_CHAT_MODEL=gpt-4o-mini
OPENAI_EMBEDDING_MODEL=text-embedding-3-small
```

```text
SEM_CACHE_PROVIDER=oci
OCI_GENAI_COMPARTMENT_ID=...
OCI_GENAI_REGION=...
OCI_GENAI_CHAT_MODEL_ID=...
OCI_GENAI_EMBEDDING_MODEL_ID=...
```

Provider mode must not change cache policy. Model names remain part of cache scope.
