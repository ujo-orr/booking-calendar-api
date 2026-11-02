CREATE TABLE reservation_slots (
    id BIGINT NOT NULL AUTO_INCREMENT,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_slot_times UNIQUE (starts_at, ends_at),
    CONSTRAINT ck_slot_times CHECK (starts_at < ends_at)
);

CREATE TABLE reservations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    slot_id BIGINT NOT NULL,
    active_slot_id BIGINT,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    cancelled_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_reservation_member FOREIGN KEY (member_id) REFERENCES members (id),
    CONSTRAINT fk_reservation_slot FOREIGN KEY (slot_id) REFERENCES reservation_slots (id),
    CONSTRAINT fk_reservation_active_slot FOREIGN KEY (active_slot_id) REFERENCES reservation_slots (id),
    CONSTRAINT uk_reservation_active_slot UNIQUE (active_slot_id),
    CONSTRAINT ck_reservation_state CHECK (
        (status = 'CONFIRMED' AND active_slot_id IS NOT NULL
            AND active_slot_id = slot_id AND cancelled_at IS NULL)
        OR (status = 'CANCELLED' AND active_slot_id IS NULL AND cancelled_at IS NOT NULL)
    ),
    CONSTRAINT ck_reservation_cancel_time CHECK (cancelled_at IS NULL OR cancelled_at >= created_at)
);

CREATE INDEX idx_reservation_member_created ON reservations (member_id, created_at, id);
CREATE INDEX idx_reservation_slot ON reservations (slot_id);
