-- Migration 0002: 3-mode pipeline, priority queue, preview rate limits, and server-side SHA-256 deduplication

ALTER TABLE render_jobs ADD COLUMN job_type TEXT NOT NULL DEFAULT 'final_export';
ALTER TABLE render_jobs ADD COLUMN priority INTEGER NOT NULL DEFAULT 100;
ALTER TABLE render_jobs ADD COLUMN request_fingerprint TEXT;
ALTER TABLE render_jobs ADD COLUMN preview_session_id TEXT;
ALTER TABLE render_jobs ADD COLUMN dedup_cache_hit INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_jobs_priority_queue
    ON render_jobs(status, priority DESC, created_at ASC);

CREATE TABLE IF NOT EXISTS preview_sessions (
    preview_session_id TEXT PRIMARY KEY,
    merchant_id TEXT NOT NULL,
    template_id TEXT NOT NULL,
    template_version TEXT NOT NULL,
    proxy_renders_used INTEGER NOT NULL DEFAULT 0,
    max_proxy_renders INTEGER NOT NULL DEFAULT 3,
    expires_at TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS rate_limit_counters (
    bucket_key TEXT PRIMARY KEY,
    hit_count INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS render_cache_index (
    request_fingerprint TEXT PRIMARY KEY,
    job_type TEXT NOT NULL,
    output_r2_key TEXT NOT NULL,
    controle_r2_key TEXT,
    output_sha256 TEXT,
    music_id INTEGER,
    expires_at TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
