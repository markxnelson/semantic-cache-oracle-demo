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

The expected reports are:

```text
reports/generated/validation-events.csv
reports/generated/validation-summary.json
reports/generated/validation-summary.md
reports/generated/benchmark-lite-events.csv
reports/generated/benchmark-lite-summary.md
```

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
