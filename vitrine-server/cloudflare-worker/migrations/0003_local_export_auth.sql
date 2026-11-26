-- Migration 0003: Credit-gated local Android export authorization, idempotency, and seamless server fallback

CREATE TABLE IF NOT EXISTS local_exports (
    export_id TEXT PRIMARY KEY,
    merchant_id TEXT NOT NULL,
    client_export_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    template_id TEXT NOT NULL,
    template_version TEXT NOT NULL,
    music_id INTEGER,
    export_lease_hmac TEXT NOT NULL,
    status TEXT NOT NULL, -- 'AUTHORIZED_LOCAL' | 'COMPLETED_LOCAL' | 'FALLBACK_QUEUED' | 'REFUNDED'
    output_sha256 TEXT,
    size_bytes INTEGER,
    render_duration_ms INTEGER,
    used_hardware_encoder INTEGER,
    fallback_job_id TEXT,
    expires_at TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(merchant_id, client_export_id)
);

ALTER TABLE render_jobs ADD COLUMN prepaid_export_id TEXT;
