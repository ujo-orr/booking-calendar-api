CREATE TABLE calendar_connections (
    member_id BIGINT NOT NULL,
    encrypted_access_token TEXT NOT NULL,
    encrypted_refresh_token TEXT,
    access_token_expires_at DATETIME(6) NOT NULL,
    scopes VARCHAR(2000) NOT NULL,
    reauth_required BOOLEAN NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (member_id),
    CONSTRAINT fk_calendar_connection_member FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE
);

CREATE TABLE reservation_calendar_sync (
    reservation_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    google_event_id VARCHAR(1024),
    last_error VARCHAR(40),
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (reservation_id),
    CONSTRAINT fk_calendar_sync_reservation FOREIGN KEY (reservation_id) REFERENCES reservations (id) ON DELETE CASCADE,
    CONSTRAINT ck_calendar_sync_status CHECK (status IN (
        'NOT_CONNECTED', 'PENDING_CREATE', 'CREATING', 'SYNCED', 'CREATE_FAILED',
        'PENDING_DELETE', 'DELETING', 'DELETED', 'DELETE_FAILED'
    )),
    CONSTRAINT ck_calendar_sync_event CHECK (
        status NOT IN ('SYNCED', 'PENDING_DELETE', 'DELETING', 'DELETE_FAILED') OR google_event_id IS NOT NULL
    )
);

INSERT INTO reservation_calendar_sync (reservation_id, status, updated_at)
SELECT id, CASE WHEN status = 'CANCELLED' THEN 'DELETED' ELSE 'NOT_CONNECTED' END, created_at
FROM reservations;
