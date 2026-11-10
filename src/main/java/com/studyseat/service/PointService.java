package com.studyseat.service;

import com.studyseat.domain.PointLedger;
import com.studyseat.repository.PointLedgerRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포인트 원장. 잔액을 따로 저장하지 않고 원장 합계로 계산한다.
 * (reservation_id, reason) 유니크 인덱스가 같은 예약의 환불·적립이 두 번 기록되는 것을 막는다.
 */
@Service
public class PointService {

    private final PointLedgerRepository ledger;

    public PointService(PointLedgerRepository ledger) {
        this.ledger = ledger;
    }

    @Transactional(readOnly = true)
    public long balance(Long memberId) {
        return ledger.balance(memberId);
    }

    @Transactional(readOnly = true)
    public boolean hasRecord(Long reservationId, String reason) {
        return ledger.existsByReservationIdAndReason(reservationId, reason);
    }

    @Transactional(readOnly = true)
    public List<PointLedger> history(Long memberId) {
        return ledger.findByMemberIdOrderByIdDesc(memberId);
    }

    @Transactional
    public void charge(Long memberId, int amount, LocalDateTime now) {
        ledger.save(new PointLedger(memberId, amount, PointLedger.CHARGE, null, now));
    }

    /** 충전. 큰 금액은 보너스(예: 5만원 이상 +10%)를 BONUS로 따로 적립한다. 허용 금액은 화면과 같은 목록뿐이다. */
    @Transactional
    public int chargePackage(Long memberId, int amount, LocalDateTime now) {
        int bonusPercent;
        switch (amount) {
            case 10_000 -> bonusPercent = 0;
            case 30_000 -> bonusPercent = 5;
            case 50_000 -> bonusPercent = 10;
            case 100_000 -> bonusPercent = 12;
            default -> throw new IllegalArgumentException("충전할 수 없는 금액입니다");
        }
        ledger.save(new PointLedger(memberId, amount, PointLedger.CHARGE, null, now));
        int bonus = amount * bonusPercent / 100;
        if (bonus > 0) {
            ledger.save(new PointLedger(memberId, bonus, "BONUS", null, now));
        }
        return bonus;
    }

    /** 예약 시 사용. 호출하는 쪽이 회원 행을 잠근 상태에서 잔액을 확인해야 한다. */
    @Transactional
    public void use(Long memberId, Long reservationId, int amount, LocalDateTime now) {
        useFor(memberId, reservationId, amount, PointLedger.USE, now);
    }

    /** 사유를 지정해 사용(예: 연장은 EXTEND). 같은 예약·같은 사유는 한 번만 기록된다. */
    @Transactional
    public void useFor(Long memberId, Long reservationId, int amount, String reason, LocalDateTime now) {
        if (amount <= 0) {
            return;
        }
        if (ledger.balance(memberId) < amount) {
            throw new InsufficientPointException("포인트가 부족합니다 (필요 " + String.format("%,d", amount) + "P)");
        }
        ledger.save(new PointLedger(memberId, -amount, reason, reservationId, now));
    }

    @Transactional
    public void refund(Long memberId, Long reservationId, int amount, LocalDateTime now) {
        record(memberId, reservationId, amount, PointLedger.REFUND, now);
    }

    @Transactional
    public void earn(Long memberId, Long reservationId, int amount, LocalDateTime now) {
        record(memberId, reservationId, amount, PointLedger.EARN, now);
    }

    private void record(Long memberId, Long reservationId, int amount, String reason, LocalDateTime now) {
        if (amount <= 0 || ledger.existsByReservationIdAndReason(reservationId, reason)) {
            return;
        }
        // 호출하는 쪽이 예약 행을 잠근 상태라 같은 예약의 같은 사유는 동시에 들어오지 않는다.
        // 그래도 중복이 들어오면 유니크 인덱스가 막고 트랜잭션 전체가 롤백된다(조용히 삼키면 rollback-only와 어긋남).
        ledger.save(new PointLedger(memberId, amount, reason, reservationId, now));
    }
}
