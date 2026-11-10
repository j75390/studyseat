package com.studyseat.web;

import com.studyseat.domain.Branch;
import com.studyseat.domain.CafePolicy;
import com.studyseat.domain.Reservation;
import com.studyseat.repository.CafePolicyRepository;
import com.studyseat.service.BranchService;
import com.studyseat.service.MemberService;
import com.studyseat.service.PointService;
import com.studyseat.service.ReservationService;
import com.studyseat.service.ReviewService;
import java.security.Principal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 지점 탐색(지도) → 지점 상세 → 날짜·시간·좌석 선택 → 예약. */
@Controller
public class BranchController {

    private final BranchService branchService;
    private final MemberService memberService;
    private final ReservationService reservations;
    private final ReviewService reviewService;
    private final PointService points;
    private final CafePolicyRepository policies;
    private final Clock clock;

    public BranchController(BranchService branchService, MemberService memberService, ReservationService reservations,
                            ReviewService reviewService, PointService points, CafePolicyRepository policies,
                            Clock clock) {
        this.branchService = branchService;
        this.memberService = memberService;
        this.reservations = reservations;
        this.reviewService = reviewService;
        this.points = points;
        this.policies = policies;
        this.clock = clock;
    }

    private Long memberIdOrNull(Principal p) {
        return p == null ? null : memberService.getByLoginId(p.getName()).getId();
    }

    /** 홈: 로고·검색·바로가기·지금 여유 있는 지점·내 지점·이용 방법·후기 */
    @GetMapping("/")
    String home(Principal principal, Model model) {
        LocalDateTime now = LocalDateTime.now(clock);
        Long memberId = memberIdOrNull(principal);
        List<BranchService.BranchCard> cards = branchService.list(now, memberId);
        int totalSeats = 0;
        int freeSeats = 0;
        for (BranchService.BranchCard c : cards) {
            totalSeats += c.totalSeats();
            if (c.openNow()) {
                freeSeats += c.freeNow();
            }
        }
        // 지금 여유 있는 지점: 영업 중이고 좌석이 넉넉한 곳 중 잔여가 많은 순
        List<BranchService.BranchCard> relaxed = cards.stream()
                .filter(c -> c.openNow() && c.totalSeats() >= 10)
                .sorted((a, b) -> Integer.compare(b.freeNow() * 100 / Math.max(1, b.totalSeats()), a.freeNow() * 100 / Math.max(1, a.totalSeats())))
                .limit(6).toList();
        model.addAttribute("now", now);
        model.addAttribute("branchCount", cards.size());
        model.addAttribute("totalSeats", totalSeats);
        model.addAttribute("freeSeats", freeSeats);
        model.addAttribute("relaxed", relaxed);
        model.addAttribute("favorites", cards.stream().filter(BranchService.BranchCard::favorite).limit(6).toList());
        model.addAttribute("regions", cards.stream().collect(java.util.stream.Collectors.groupingBy(BranchService.BranchCard::sido,
                java.util.TreeMap::new, java.util.stream.Collectors.counting())));
        model.addAttribute("reviews", reviewService.list(null).stream().limit(3).toList());
        return "home";
    }

    /** 지점 찾기: 지도와 지점 목록. 지점이 수천 개라 서버는 틀만 그리고 화면이 /api/branches를 받아 그린다. */
    @GetMapping("/branches")
    String branchMap(Model model) {
        model.addAttribute("now", LocalDateTime.now(clock));
        return "branches";
    }
    @GetMapping("/branches/{id}")
    String detail(@PathVariable Long id, Principal principal, Model model) {
        LocalDateTime now = LocalDateTime.now(clock);
        BranchService.Detail d = branchService.detail(id, now, memberIdOrNull(principal));
        model.addAttribute("d", d);
        model.addAttribute("reviews", reviewService.list(id));
        model.addAttribute("rating", reviewService.averageRating(id));
        model.addAttribute("hour", now.getHour());
        return "branch";
    }

    @PostMapping("/branches/{id}/favorite")
    String favorite(@PathVariable Long id, Principal principal, RedirectAttributes ra) {
        Long memberId = memberIdOrNull(principal);
        Flash.run(ra, null, () -> {
            boolean on = branchService.toggleFavorite(memberId, id);
            ra.addFlashAttribute("ok", on ? "내 지점에 등록했어요" : "내 지점에서 뺐어요");
        });
        return "redirect:/branches/" + id;
    }

    @GetMapping("/branches/{id}/book")
    String book(@PathVariable Long id,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                @RequestParam(required = false) Integer start, @RequestParam(required = false) Integer hours,
                Principal principal, Model model) {
        LocalDateTime now = LocalDateTime.now(clock);
        Long memberId = memberIdOrNull(principal);
        BranchService.Detail d = branchService.detail(id, now, memberId);
        Branch b = d.card().branch();
        CafePolicy policy = policies.findById(1L).orElseThrow();

        // 날짜: 기본은 오늘. 오늘 영업이 모두 끝났으면 내일.
        LocalDate today = now.toLocalDate();
        boolean todayOver = b.getCloseHour() < 24 && now.getHour() >= b.getCloseHour();
        LocalDate day = date != null ? date : today;
        if (day.isBefore(today) || (day.equals(today) && todayOver)) {
            day = todayOver ? today.plusDays(1) : today;
        }
        if (day.isAfter(today.plusDays(13))) {
            day = today.plusDays(13);
        }
        // 시작 시각: 오늘이면 지금 시각 이후, 아니면 영업 시작
        int minStart = day.equals(today) ? Math.max(b.getOpenHour(), now.getHour()) : b.getOpenHour();
        int s = start != null ? start : Math.max(minStart, day.equals(today) ? now.getHour() : 10);
        s = Math.max(minStart, Math.min(s, b.getCloseHour() - 1));
        int maxHours = Math.min(policy.getMaxHoursPerRsv(), b.getCloseHour() - s);
        int h = hours != null ? hours : 2;
        h = Math.max(1, Math.min(h, maxHours));

        List<Integer> startOptions = new ArrayList<>();
        for (int x = minStart; x < b.getCloseHour(); x++) {
            startOptions.add(x);
        }
        List<LocalDate> dates = new ArrayList<>();
        for (int i = todayOver ? 1 : 0; i < 14; i++) {
            dates.add(today.plusDays(i));
        }
        List<Integer> durations = new ArrayList<>();
        for (int x = 1; x <= maxHours; x++) {
            durations.add(x);
        }

        model.addAttribute("d", d);
        model.addAttribute("branch", b);
        model.addAttribute("plan", PlanMapper.of(branchService.plan(id, day, s, h, memberId)));
        model.addAttribute("date", day);
        model.addAttribute("start", s);
        model.addAttribute("hours", h);
        model.addAttribute("end", s + h);
        model.addAttribute("dates", dates);
        model.addAttribute("startOptions", startOptions);
        model.addAttribute("durations", durations);
        model.addAttribute("balance", memberId == null ? null : points.balance(memberId));
        return "book";
    }

    @PostMapping("/reserve")
    String reserve(@RequestParam Long seatId, @RequestParam Long branchId,
                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                   @RequestParam int start, @RequestParam int hours, Principal principal, RedirectAttributes ra) {
        Long memberId = memberIdOrNull(principal);
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            Reservation r = reservations.reserve(memberId, seatId, date.atTime(start, 0), hours, now);
            ra.addFlashAttribute("ok", "예약이 완료되었습니다. 입실 QR을 확인하세요");
            return "redirect:/reservations/" + r.getId();
        } catch (RuntimeException e) {
            if (e instanceof com.studyseat.service.SeatTakenException
                    || e instanceof com.studyseat.service.InsufficientPointException
                    || e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
                ra.addFlashAttribute("error", e.getMessage());
                return "redirect:/branches/" + branchId + "/book?date=" + date + "&start=" + start + "&hours=" + hours;
            }
            throw e;
        }
    }
}
