package com.studyseat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.studyseat.domain.Member;
import com.studyseat.domain.Reservation;
import com.studyseat.domain.ReservationStatus;
import com.studyseat.domain.Seat;
import com.studyseat.repository.MemberRepository;
import com.studyseat.repository.ReservationRepository;
import com.studyseat.repository.SeatRepository;
import com.studyseat.service.BranchService;
import com.studyseat.service.PointService;
import com.studyseat.service.ReservationService;
import com.studyseat.service.SeatTakenException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 지점·도면·연장·키오스크·충전 같은 "진짜 서비스" 기능을 확인한다. */
@SpringBootTest(properties = "studyseat.demo=false")
class FeatureTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 9, 8, 0);
    static final LocalDateTime START = LocalDateTime.of(2026, 11, 10, 10, 0);

    @Autowired ReservationService service;
    @Autowired BranchService branchService;
    @Autowired PointService points;
    @Autowired MemberRepository members;
    @Autowired SeatRepository seats;
    @Autowired ReservationRepository reservations;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.studyseat.service.MemberService memberService;

    @AfterEach
    void cleanUp() {
        TestDb.clean(jdbc);
        jdbc.update("UPDATE seat SET active = TRUE");
    }

    private Long member(String loginId, int point) {
        Long id = members.save(new Member(loginId, "pw", "테스터", null, "MEMBER", NOW)).getId();
        if (point > 0) {
            points.charge(id, point, NOW);
        }
        return id;
    }

    private Seat seatOf(long zoneId, int index) {
        return seats.findByZoneIdAndActiveTrueOrderByLabel(zoneId).stream()
                .filter(s -> "A".equals(s.getGender()) && !s.isFixedSeat()).toList().get(index);
    }

    @Test
    void 연장은_예약당_한_번만_되고_바로_뒤_시간이_차_있으면_실패하며_취소하면_전체가_환불된다() {
        Long m = member("ext", 100_000);
        Seat seat = seatOf(1, 0); // 강남점 일반석, 시간당 2,000P
        Reservation r = service.reserve(m, seat.getId(), START, 2, NOW);
        assertThat(points.balance(m)).isEqualTo(100_000 - 4_000);

        service.extend(m, r.getId(), 1, NOW);
        Reservation after = reservations.findById(r.getId()).orElseThrow();
        assertThat(after.getEndAt()).isEqualTo(START.plusHours(3));
        assertThat(after.getUsedPoint()).isEqualTo(6_000);
        assertThat(points.balance(m)).isEqualTo(100_000 - 6_000);

        assertThatThrownBy(() -> service.extend(m, r.getId(), 1, NOW)).isInstanceOf(IllegalStateException.class);

        service.cancel(m, r.getId(), NOW);
        assertThat(points.balance(m)).isEqualTo(100_000); // 연장분까지 전액 환불
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_slot", Integer.class)).isZero();
    }

    @Test
    void 연장하려는_바로_뒤_시간에_다른_예약이_있으면_연장되지_않고_포인트도_그대로다() {
        Long a = member("extA", 100_000);
        Long b = member("extB", 100_000);
        Seat seat = seatOf(1, 1);
        Reservation r = service.reserve(a, seat.getId(), START, 1, NOW); // 10~11시
        service.reserve(b, seat.getId(), START.plusHours(1), 1, NOW); // 11~12시

        assertThatThrownBy(() -> service.extend(a, r.getId(), 1, NOW)).isInstanceOf(SeatTakenException.class);

        assertThat(points.balance(a)).isEqualTo(100_000 - 2_000);
        assertThat(reservations.findById(r.getId()).orElseThrow().getEndAt()).isEqualTo(START.plusHours(1));
    }

    @Test
    void 키오스크는_예약_코드로_입실_퇴실하고_틀린_코드는_거부한다() {
        Long m = member("kiosk", 100_000);
        Reservation r = service.reserve(m, seatOf(1, 2).getId(), START, 1, NOW);
        assertThat(r.getCode()).matches("\\d{6}");

        assertThatThrownBy(() -> service.kioskCheckIn("000000x", START)).isInstanceOf(IllegalArgumentException.class);
        service.kioskCheckIn(r.getCode(), START);
        assertThat(reservations.findById(r.getId()).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
        service.kioskCheckOut(r.getCode(), START.plusMinutes(50));
        assertThat(reservations.findById(r.getId()).orElseThrow().getStatus()).isEqualTo(ReservationStatus.DONE);
    }

    @Test
    void 지점마다_운영_시간이_다르다_홍대점은_24시간_강남점은_9시부터() {
        Long m = member("hours", 100_000);
        Seat hongdae = seatOf(4, 0); // 홍대점(24시간) 일반석
        Seat gangnam = seatOf(1, 3);
        LocalDateTime midnight = LocalDateTime.of(2026, 11, 10, 0, 0);

        service.reserve(m, hongdae.getId(), midnight, 3, NOW); // 0~3시 가능
        service.reserve(m, hongdae.getId(), LocalDateTime.of(2026, 11, 10, 22, 0), 2, NOW); // 22~24시 가능
        assertThatThrownBy(() -> service.reserve(m, gangnam.getId(), midnight, 3, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reserve(m, gangnam.getId(), LocalDateTime.of(2026, 11, 10, 22, 0), 2, NOW))
                .isInstanceOf(IllegalArgumentException.class); // 강남점은 23시 마감
    }

    @Test
    void 구역마다_시간당_가격이_다르다_스터디룸은_8000P() {
        Long m = member("price", 100_000);
        Seat room = seatOf(2, 0); // 강남점 스터디룸
        Seat booth = seatOf(3, 0); // 강남점 프리미엄 부스

        Reservation r1 = service.reserve(m, room.getId(), START, 2, NOW);
        Reservation r2 = service.reserve(m, booth.getId(), START, 2, NOW);

        assertThat(r1.getUsedPoint()).isEqualTo(16_000);
        assertThat(r2.getUsedPoint()).isEqualTo(6_000);
        assertThat(points.balance(m)).isEqualTo(100_000 - 22_000);
    }

    @Test
    void 지점_목록은_지금_시간의_잔여석과_영업_여부를_보여준다() {
        Long m = member("list", 100_000);
        LocalDateTime tenThirty = START.plusMinutes(30);
        service.reserve(m, seatOf(1, 0).getId(), START, 1, NOW); // 강남점 10시 한 자리
        service.reserve(m, seatOf(3, 0).getId(), START, 1, NOW); // 강남점 10시 한 자리 더

        BranchService.BranchCard gangnam = card(tenThirty, 1L);
        BranchService.BranchCard hongdae = card(tenThirty, 2L);
        assertThat(gangnam.totalSeats()).isEqualTo(21); // 22석 중 고정석 1석(A-04)은 시간권 좌석이 아니라 뺀다
        assertThat(gangnam.freeNow()).isEqualTo(19);
        assertThat(gangnam.openNow()).isTrue();
        assertThat(hongdae.freeNow()).isEqualTo(hongdae.totalSeats()); // 예약 없음

        BranchService.BranchCard lateGangnam = card(LocalDateTime.of(2026, 11, 10, 23, 30), 1L);
        assertThat(lateGangnam.openNow()).isFalse(); // 강남점은 23시 마감
        assertThat(lateGangnam.freeNow()).isZero();
        assertThat(card(LocalDateTime.of(2026, 11, 10, 23, 30), 2L).openNow()).isTrue(); // 홍대점은 24시간
    }

    private BranchService.BranchCard card(LocalDateTime at, long branchId) {
        return branchService.list(at, null).stream().filter(c -> c.branch().getId() == branchId).findFirst().orElseThrow();
    }

    @Test
    void 도면은_선택한_시간_구간_기준으로_내_예약_예약됨_비활성을_구분한다() {
        Long me = member("plan", 100_000);
        Long other = member("planOther", 100_000);
        Seat mine = seatOf(1, 0);
        Seat theirs = seatOf(1, 1);
        Seat off = seatOf(1, 2);
        service.reserve(me, mine.getId(), START, 2, NOW); // 10~12
        service.reserve(other, theirs.getId(), START.plusHours(1), 2, NOW); // 11~13
        jdbc.update("UPDATE seat SET active = FALSE WHERE id = ?", off.getId());

        LocalDate day = START.toLocalDate();
        BranchService.PlanView forMe = branchService.plan(1L, day, 10, 2, me);
        assertThat(state(forMe, mine)).isEqualTo(BranchService.SeatState.MINE);
        assertThat(state(forMe, theirs)).isEqualTo(BranchService.SeatState.TAKEN); // 11시에 걸쳐 있음
        assertThat(state(forMe, off)).isEqualTo(BranchService.SeatState.INACTIVE);
        // 총량에서 비활성·고정석은 빠지고, 성별을 등록하지 않은 이 회원에게는 성별 전용 5석(여 A-01·02·09·10, 남 A-08)이 선택 불가
        assertThat(forMe.freeCount()).isEqualTo(forMe.totalCount() - 2 - 5);

        // 구간을 옮기면 같은 좌석이 비어 있게 보인다: 13~14시에는 둘 다 빈 자리
        BranchService.PlanView later = branchService.plan(1L, day, 13, 1, me);
        assertThat(state(later, mine)).isEqualTo(BranchService.SeatState.FREE);
        assertThat(state(later, theirs)).isEqualTo(BranchService.SeatState.FREE);
        // 비로그인에게는 내 예약도 '예약됨'
        assertThat(state(branchService.plan(1L, day, 10, 2, null), mine)).isEqualTo(BranchService.SeatState.TAKEN);
    }

    private BranchService.SeatState state(BranchService.PlanView plan, Seat seat) {
        return plan.seats().stream().filter(v -> v.seat().getId().equals(seat.getId())).findFirst().orElseThrow().state();
    }

    @Test
    void 내_지점은_누를_때마다_등록_해제된다() {
        Long m = member("fav", 0);
        assertThat(branchService.toggleFavorite(m, 2L)).isTrue();
        assertThat(branchService.favoriteIds(m)).containsExactly(2L);
        assertThat(card(NOW, 2L).favorite()).isFalse(); // 로그인 정보 없이 조회하면 표시되지 않는다
        assertThat(branchService.list(NOW, m).stream().filter(c -> c.favorite()).count()).isEqualTo(1);
        assertThat(branchService.toggleFavorite(m, 2L)).isFalse();
        assertThat(branchService.favoriteIds(m)).isEmpty();
        assertThatThrownBy(() -> branchService.toggleFavorite(m, 99L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 충전은_정해진_금액만_가능하고_큰_금액은_보너스가_붙는다() {
        Long m = member("charge", 0);
        assertThat(points.chargePackage(m, 10_000, NOW)).isZero();
        assertThat(points.chargePackage(m, 50_000, NOW)).isEqualTo(5_000);
        assertThat(points.balance(m)).isEqualTo(10_000 + 50_000 + 5_000);
        assertThatThrownBy(() -> points.chargePackage(m, 12_345, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(points.balance(m)).isEqualTo(65_000); // 거부된 충전은 반영되지 않음
    }

    // ---------- 실제 좌석 선택 화면 같은 규칙: 고정석 · 성별 전용 · 퇴실예정석 ----------

    private Long memberWithGender(String loginId, String gender) {
        Member m = new Member(loginId, "pw", "테스터", null, "MEMBER", NOW);
        m.setGender(gender);
        Long id = members.save(m).getId();
        points.charge(id, 100_000, NOW);
        return id;
    }

    private Seat seatLabeled(String label) {
        return seats.findByZoneIdAndActiveTrueOrderByLabel(1L).stream().filter(s -> s.getLabel().equals(label)).findFirst().orElseThrow();
    }

    @Test
    void 고정석은_시간권으로_예약할_수_없다() {
        Long m = memberWithGender("fix", "F");
        Seat fixed = seatLabeled("A-04"); // 일반석 10번 규칙: 번호 % 10 == 4
        assertThat(fixed.isFixedSeat()).isTrue();

        assertThatThrownBy(() -> service.reserve(m, fixed.getId(), START, 1, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("고정석");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class)).isZero();
    }

    @Test
    void 성별_전용_좌석은_같은_성별로_등록한_회원만_예약한다() {
        Long woman = memberWithGender("woman", "F");
        Long man = memberWithGender("man", "M");
        Long unknown = memberWithGender("unknown", null);
        Seat femaleSeat = seatLabeled("A-01"); // 번호 % 8 == 1 → 여성 전용
        Seat maleSeat = seatLabeled("A-08");   // 번호 % 8 == 0 → 남성 전용
        assertThat(femaleSeat.getGender()).isEqualTo("F");
        assertThat(maleSeat.getGender()).isEqualTo("M");

        assertThatThrownBy(() -> service.reserve(man, femaleSeat.getId(), START, 1, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("여성 전용");
        assertThatThrownBy(() -> service.reserve(woman, maleSeat.getId(), START, 1, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("남성 전용");
        assertThatThrownBy(() -> service.reserve(unknown, femaleSeat.getId(), START, 1, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("성별이 없어");

        service.reserve(woman, femaleSeat.getId(), START, 1, NOW);
        service.reserve(man, maleSeat.getId(), START, 1, NOW);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class)).isEqualTo(2);
    }

    @Test
    void 도면은_성별_전용_고정석을_구분해_보여준다() {
        Long woman = memberWithGender("viewW", "F");
        Long man = memberWithGender("viewM", "M");
        LocalDate day = START.toLocalDate();
        Seat female = seatLabeled("A-01");
        Seat fixed = seatLabeled("A-04");
        Seat ordinary = seatLabeled("A-03");

        BranchService.PlanView forWoman = branchService.plan(1L, day, 10, 1, woman);
        assertThat(state(forWoman, female)).isEqualTo(BranchService.SeatState.FREE);
        assertThat(state(forWoman, fixed)).isEqualTo(BranchService.SeatState.FIXED);
        assertThat(state(forWoman, ordinary)).isEqualTo(BranchService.SeatState.FREE);

        BranchService.PlanView forMan = branchService.plan(1L, day, 10, 1, man);
        assertThat(state(forMan, female)).isEqualTo(BranchService.SeatState.RESTRICTED);
        // 고정석은 시간권 좌석이 아니므로 선택 가능 좌석 수·전체 수에서 모두 빠진다
        assertThat(forMan.totalCount()).isEqualTo(forWoman.totalCount());
        // 여성에게는 남성 전용 1석(A-08), 남성에게는 여성 전용 4석(A-01·02·09·10)이 선택 불가 → 선택 가능 좌석 수가 3석 차이
        assertThat(forWoman.freeCount() - forMan.freeCount()).isEqualTo(3);
    }

    @Test
    void 선택한_시간_바로_전까지_쓰는_좌석은_퇴실예정석으로_표시된다() {
        Long before = memberWithGender("before", "F");
        Long viewer = memberWithGender("viewer", "F");
        Seat endingSeat = seatOf(1, 0);
        Seat otherSeat = seatOf(1, 1);
        service.reserve(before, endingSeat.getId(), START.minusHours(1), 1, NOW); // 9~10시 이용 → 10시에 퇴실

        BranchService.PlanView plan = branchService.plan(1L, START.toLocalDate(), 10, 2, viewer);
        BranchService.SeatView ending = plan.seats().stream().filter(v -> v.seat().getId().equals(endingSeat.getId())).findFirst().orElseThrow();
        BranchService.SeatView other = plan.seats().stream().filter(v -> v.seat().getId().equals(otherSeat.getId())).findFirst().orElseThrow();

        assertThat(ending.state()).isEqualTo(BranchService.SeatState.FREE); // 10시에는 비어 있어 선택 가능
        assertThat(ending.endingSoon()).isTrue();
        assertThat(other.endingSoon()).isFalse();
        // 이용 중인 시간(9~11시)을 고르면 예약됨이라 퇴실예정 표시가 없다
        BranchService.PlanView busy = branchService.plan(1L, START.toLocalDate(), 9, 1, viewer);
        BranchService.SeatView during = busy.seats().stream().filter(v -> v.seat().getId().equals(endingSeat.getId())).findFirst().orElseThrow();
        assertThat(during.state()).isEqualTo(BranchService.SeatState.TAKEN);
        assertThat(during.endingSoon()).isFalse();
    }

    @Test
    void 성별은_여_남만_저장하고_다른_값은_입력_안_함으로_본다() {
        com.studyseat.service.MemberService ms = memberService;
        assertThat(ms.signup("gw", "password1", "여성", null, "F", NOW).getGender()).isEqualTo("F");
        assertThat(ms.signup("gm", "password1", "남성", null, "M", NOW).getGender()).isEqualTo("M");
        assertThat(ms.signup("gx", "password1", "이상", null, "X", NOW).getGender()).isNull();
        assertThat(ms.signup("gn", "password1", "없음", null, NOW).getGender()).isNull();
    }}
