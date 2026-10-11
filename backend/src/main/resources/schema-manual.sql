-- Manual copy of schema.sql for SQL clients (psql, DBeaver "Execute script"): same statements, ";" separators.
-- The app itself runs schema.sql at startup. Keep both files in sync.


CREATE EXTENSION IF NOT EXISTS timescaledb
;

CREATE TABLE IF NOT EXISTS trade (
  trade_ref TEXT PRIMARY KEY, side TEXT NOT NULL, isin TEXT NOT NULL, security_name TEXT,
  quantity NUMERIC NOT NULL, price NUMERIC NOT NULL, currency TEXT NOT NULL,
  exec_ts TIMESTAMPTZ NOT NULL, trade_date DATE NOT NULL, settle_date DATE NOT NULL,
  counterparty_bic TEXT NOT NULL, agent_id TEXT NOT NULL, pset_bic TEXT NOT NULL,
  safe_account TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'NEW')
;

CREATE TABLE IF NOT EXISTS swift_message (
  msg_id UUID NOT NULL DEFAULT gen_random_uuid(), event_ts TIMESTAMPTZ NOT NULL DEFAULT now(),
  trade_ref TEXT NOT NULL, direction TEXT NOT NULL, msg_type TEXT NOT NULL,
  raw_text TEXT NOT NULL, parsed JSONB, status_code TEXT, reason_code TEXT,
  PRIMARY KEY (msg_id, event_ts))
;

CREATE TABLE IF NOT EXISTS trade_event (
  event_ts TIMESTAMPTZ NOT NULL DEFAULT now(), trade_ref TEXT NOT NULL, event_type TEXT NOT NULL,
  reason_code TEXT, counterparty_bic TEXT, minutes_pending NUMERIC, detail JSONB)
;

CREATE TABLE IF NOT EXISTS investigation (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(), trade_ref TEXT NOT NULL,
  started_at TIMESTAMPTZ DEFAULT now(), steps JSONB DEFAULT '[]', result JSONB,
  used_recipe TEXT, status TEXT DEFAULT 'RUNNING')
;

SELECT create_hypertable('swift_message', by_range('event_ts'), if_not_exists => TRUE)
;

SELECT create_hypertable('trade_event', by_range('event_ts'), if_not_exists => TRUE)
;

CREATE INDEX IF NOT EXISTS swift_message_trade_ref_idx ON swift_message (trade_ref, event_ts)
;

CREATE INDEX IF NOT EXISTS trade_event_trade_ref_idx ON trade_event (trade_ref, event_ts)
;

CREATE MATERIALIZED VIEW IF NOT EXISTS settle_stats
WITH (timescaledb.continuous) AS
SELECT time_bucket('1 day', event_ts) AS bucket,
       counterparty_bic,
       reason_code,
       avg(minutes_pending) AS avg_minutes,
       count(*) AS samples
FROM trade_event
WHERE event_type = 'SETTLED'
GROUP BY bucket, counterparty_bic, reason_code
WITH NO DATA
;

-- History seed: ~2000 synthetic SETTLED events over the last 60 days.
-- BRKBUS33XXX + LACK is deliberately slower (300-600 min vs 30-400 min).
DO $$
BEGIN
  IF (SELECT count(*) FROM trade_event WHERE trade_ref LIKE 'HIST%') < 100 THEN
    INSERT INTO trade_event (event_ts, trade_ref, event_type, reason_code, counterparty_bic, minutes_pending)
    SELECT ts, 'HIST' || lpad(n::text, 6, '0'), 'SETTLED', reason, cpty,
           CASE WHEN cpty = 'BRKBUS33XXX' AND reason = 'LACK'
                THEN round((300 + random() * 300)::numeric, 0)
                ELSE round((30 + random() * 370)::numeric, 0) END
    FROM (
      SELECT n,
             now() - (random() * interval '60 days') AS ts,
             (ARRAY['BRKRUS33XXX','BRKBUS33XXX','BRKCUS33XXX','BRKDUS33XXX'])[1 + floor(random() * 4)::int] AS cpty,
             (ARRAY['LACK','CLAC','MONY',NULL])[1 + floor(random() * 4)::int] AS reason
      FROM generate_series(1, 2000) AS n
    ) s;
  END IF;
END $$
;

CALL refresh_continuous_aggregate('settle_stats', NULL, NULL)
;

-- When the trade was loaded into Settle (grid sorts newest first). clock_timestamp() so trades in one upload differ.
ALTER TABLE trade ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
;
