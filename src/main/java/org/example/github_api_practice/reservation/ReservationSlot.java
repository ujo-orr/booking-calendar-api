package org.example.github_api_practice.reservation;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "reservation_slots", uniqueConstraints = @UniqueConstraint(
        name = "uk_slot_times", columnNames = {"starts_at", "ends_at"}))
public class ReservationSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "starts_at", nullable = false, updatable = false)
    private Instant startsAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "ends_at", nullable = false, updatable = false)
    private Instant endsAt;

    public ReservationSlot(Instant startsAt, Instant endsAt) {
        if (startsAt == null || endsAt == null || !startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("슬롯 시작 시각은 종료 시각보다 빨라야 합니다.");
        }
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }
}
