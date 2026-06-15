WHENEVER SQLERROR EXIT SQL.SQLCODE
SET ECHO ON

CONNECT semcache_app/"SemCache_26ai_Demo"@//localhost:1521/FREEPDB1

CREATE TABLE sem_cache_entry (
  id                       RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  tenant_id                VARCHAR2(64) NOT NULL,
  prompt_hash              VARCHAR2(64) NOT NULL,
  prompt_text              CLOB NOT NULL,
  prompt_embedding         VECTOR(4, FLOAT32) NOT NULL,
  answer_text              CLOB NOT NULL,
  chat_model               VARCHAR2(128) NOT NULL,
  embedding_model          VARCHAR2(128) NOT NULL,
  embedding_dimension      NUMBER NOT NULL,
  prompt_template_version  VARCHAR2(64) NOT NULL,
  source_fingerprint       VARCHAR2(128) NOT NULL,
  policy_version           VARCHAR2(64) NOT NULL,
  status                   VARCHAR2(32) NOT NULL,
  created_at               TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  expires_at               TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX sem_cache_entry_exact_ix
ON sem_cache_entry (
  tenant_id,
  prompt_hash,
  chat_model,
  embedding_model,
  embedding_dimension,
  prompt_template_version,
  source_fingerprint,
  policy_version,
  status
);

CREATE TABLE sem_cache_event (
  id                  RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  scenario_name       VARCHAR2(128) NOT NULL,
  route_name          VARCHAR2(64) NOT NULL,
  decision            VARCHAR2(64) NOT NULL,
  reason              VARCHAR2(256) NOT NULL,
  distance            NUMBER,
  threshold           NUMBER,
  provider_calls      NUMBER NOT NULL,
  latency_ms          NUMBER NOT NULL,
  created_at          TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL
);

EXIT
