-- VITRINE VIDÉO Control Plane D1 Schema (SQLite)

CREATE TABLE IF NOT EXISTS merchants (
    merchant_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL UNIQUE,
    phone TEXT,
    credits_balance INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS credit_ledger (
    ledger_id TEXT PRIMARY KEY,
    merchant_id TEXT NOT NULL,
    delta INTEGER NOT NULL,
    reason TEXT NOT NULL, -- 'SIGNUP_BONUS' | 'PURCHASE' | 'RENDER_DEBIT' | 'RENDER_REFUND'
    ref_id TEXT NOT NULL, -- job_id or payment_tx_id
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(merchant_id, reason, ref_id)
);

CREATE TABLE IF NOT EXISTS payment_transactions (
    tx_id TEXT PRIMARY KEY,
    provider TEXT NOT NULL, -- 'fedapay' | 'kkiapay' | 'cinetpay'
    external_tx_id TEXT NOT NULL,
    merchant_id TEXT NOT NULL,
    amount_xof INTEGER NOT NULL,
    credits_granted INTEGER NOT NULL,
    status TEXT NOT NULL, -- 'VERIFIED' | 'REJECTED'
    raw_event_sha256 TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(provider, external_tx_id)
);

CREATE TABLE IF NOT EXISTS render_jobs (
    job_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL UNIQUE,
    merchant_id TEXT NOT NULL,
    template_id TEXT NOT NULL,
    template_version TEXT NOT NULL,
    status TEXT NOT NULL, -- 'PENDING_UPLOAD' | 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'FAILED'
    responses_json TEXT NOT NULL,
    captures_json TEXT NOT NULL DEFAULT '[]',
    music_id INTEGER,
    output_r2_key TEXT,
    controle_r2_key TEXT,
    output_sha256 TEXT,
    error_message TEXT,
    worker_id TEXT,
    claimed_at TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_jobs_merchant ON render_jobs(merchant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_jobs_status ON render_jobs(status, created_at ASC);

CREATE TABLE IF NOT EXISTS music_rotation_state (
    profile_id TEXT PRIMARY KEY,
    last_track_num INTEGER NOT NULL,
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);
