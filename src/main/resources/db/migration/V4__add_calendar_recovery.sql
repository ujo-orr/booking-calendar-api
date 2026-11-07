ALTER TABLE reservation_calendar_sync ADD COLUMN event_key VARCHAR(64);
ALTER TABLE reservation_calendar_sync ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE reservation_calendar_sync ADD COLUMN next_attempt_at DATETIME(6);
ALTER TABLE reservation_calendar_sync ADD COLUMN lease_token VARCHAR(36);
ALTER TABLE reservation_calendar_sync ADD COLUMN lease_until DATETIME(6);
ALTER TABLE reservation_calendar_sync ADD COLUMN last_attempt_at DATETIME(6);
ALTER TABLE reservation_calendar_sync ADD COLUMN create_dispatched BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE reservation_calendar_sync DROP CONSTRAINT ck_calendar_sync_event;
ALTER TABLE reservation_calendar_sync ADD CONSTRAINT ck_calendar_sync_event CHECK (
    status <> 'SYNCED' OR google_event_id IS NOT NULL
);
ALTER TABLE reservation_calendar_sync ADD CONSTRAINT uk_calendar_event_key UNIQUE (event_key);
ALTER TABLE reservation_calendar_sync ADD CONSTRAINT ck_calendar_attempts CHECK (attempt_count >= 0);
ALTER TABLE reservation_calendar_sync ADD CONSTRAINT ck_calendar_lease CHECK (
    (lease_token IS NULL AND lease_until IS NULL) OR (lease_token IS NOT NULL AND lease_until IS NOT NULL)
);
CREATE INDEX idx_calendar_sync_due ON reservation_calendar_sync (next_attempt_at, reservation_id);

UPDATE reservation_calendar_sync SET create_dispatched = TRUE
WHERE google_event_id IS NOT NULL OR status IN ('CREATING', 'CREATE_FAILED');

UPDATE reservation_calendar_sync SET next_attempt_at = updated_at
WHERE status IN ('PENDING_CREATE', 'PENDING_DELETE', 'CREATING', 'DELETING', 'SYNCED')
   OR (status = 'DELETED' AND google_event_id IS NOT NULL)
   OR (status IN ('CREATE_FAILED', 'DELETE_FAILED')
       AND last_error IN ('PROVIDER_UNAVAILABLE', 'OUTCOME_UNKNOWN'));

ALTER TABLE calendar_connections ADD COLUMN refresh_lease_token VARCHAR(36);
ALTER TABLE calendar_connections ADD COLUMN refresh_lease_until DATETIME(6);
ALTER TABLE calendar_connections ADD CONSTRAINT ck_calendar_refresh_lease CHECK (
    (refresh_lease_token IS NULL AND refresh_lease_until IS NULL)
    OR (refresh_lease_token IS NOT NULL AND refresh_lease_until IS NOT NULL)
);
