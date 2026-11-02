package org.example.github_api_practice.reservation;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.github_api_practice.global.error.ApiException;
import org.example.github_api_practice.global.error.ErrorCode;
import org.example.github_api_practice.member.Member;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "reservations", uniqueConstraints = @UniqueConstraint(
        name = "uk_reservation_active_slot", columnNames = "active_slot_id"))
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, updatable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false, updatable = false)
    private ReservationSlot slot;

    // 확정 예약에서만 슬롯 ID를 유지하는 UNIQUE 제약용 값
    @Column(name = "active_slot_id")
    private Long activeSlotId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    public Reservation(Member member, ReservationSlot slot, Instant createdAt) {
        this.member = member;
        this.slot = slot;
        this.activeSlotId = slot.getId();
        this.status = ReservationStatus.CONFIRMED;
        this.createdAt = createdAt;
    }

    public void cancel(Instant cancelledAt) {
        if (status != ReservationStatus.CONFIRMED) {
            throw new ApiException(ErrorCode.RESERVATION_ALREADY_CANCELLED);
        }
        this.status = ReservationStatus.CANCELLED;
        this.activeSlotId = null;
        this.cancelledAt = cancelledAt;
    }
}
