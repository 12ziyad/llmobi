-- LLMobi catalog. One row per downloadable model file.
--
-- Everything the phone needs to make a decision is precomputed here so the app
-- can stay dumb: it compares one number (min_ram_mb) and prints two strings.

DROP TABLE IF EXISTS models;
CREATE TABLE models (
  id            TEXT PRIMARY KEY,
  name          TEXT NOT NULL,
  tagline       TEXT NOT NULL,
  tier          TEXT NOT NULL,   -- tiny | fast | powerful | pro | extreme
  category      TEXT NOT NULL,   -- general | reasoning | coding | vision | multilingual
  icon_letter   TEXT NOT NULL,
  color_start   TEXT NOT NULL,
  color_end     TEXT NOT NULL,

  -- what a person reads
  size_label    TEXT NOT NULL,
  speed_hint    TEXT NOT NULL,   -- very fast | fast | steady | slow | very slow

  -- what the app computes with
  file_bytes    INTEGER NOT NULL,
  min_ram_mb    INTEGER NOT NULL,
  ctx_default   INTEGER NOT NULL,
  arch          TEXT NOT NULL,
  quant         TEXT NOT NULL,
  n_layer       INTEGER,
  n_kv_head     INTEGER,
  head_dim      INTEGER,

  -- where the bytes come from
  hf_repo       TEXT NOT NULL,
  hf_file       TEXT NOT NULL,
  url           TEXT NOT NULL,
  mirror_url    TEXT,            -- R2 copy, used first when present
  sha256        TEXT,
  license       TEXT NOT NULL,

  status        TEXT NOT NULL DEFAULT 'live',  -- live | hidden | broken
  updated_at    INTEGER NOT NULL
);

CREATE INDEX idx_models_status ON models(status);
CREATE INDEX idx_models_tier   ON models(tier);

-- Human-written copy, kept apart so re-ingesting never overwrites your words.
DROP TABLE IF EXISTS overrides;
CREATE TABLE overrides (
  id       TEXT PRIMARY KEY,
  name     TEXT,
  tagline  TEXT,
  tier     TEXT,
  category TEXT,
  hidden   INTEGER NOT NULL DEFAULT 0
);
