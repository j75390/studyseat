package com.studyseat.web;

import com.studyseat.domain.Branch;
import com.studyseat.domain.Member;
import com.studyseat.domain.Seat;
import com.studyseat.domain.Zone;
import com.studyseat.repository.BranchRepository;
import com.studyseat.repository.MemberRepository;
import com.studyseat.repository.SeatRepository;
import com.studyseat.repository.ZoneRepository;
import com.studyseat.service.BranchService;
import com.studyseat.service.InquiryService;
import com.studyseat.service.OccupancyService;
import com.studyseat.service.ReservationService;
import com.studyseat.service.ReservationViewService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 관리자 화면: 전국 현황·혼잡도 대시보드, 좌석 켜기/끄기, 예약, 문의. */
@Controller
public class AdminController {

    /** 시도별 요약 한 줄 */
    public record SidoRow(String sido, int branches, int seats, int free, int busyPercent) {
    }

    public record SeatGroup(String title, List<Seat> seats) {
    }

    private final ZoneRepository zones;
    private final BranchRepository branches;
    private final SeatRepository seats;
    private final MemberRepository members;
    private final ReservationService reservations;
    private final ReservationViewService views;
    private final InquiryService inquiries;
    private final OccupancyService occupancy;
    private final BranchService branchService;
    private final Clock clock;

    public AdminController(ZoneRepository zones, BranchRepository branches, SeatRepository seats,
                           MemberRepository members, ReservationService reservations, ReservationViewService views,
                           InquiryService inquiries, OccupancyService occupancy, BranchService branchService,
                           Clock clock) {
        this.zones = zones;
        this.branches = branches;
        this.seats = seats;
        this.members = members;
        this.reservations = reservations;
        this.views = views;
        this.inquiries = inquiries;
        this.occupancy = occupancy;
        this.branchService = branchService;
        this.clock = clock;
    }

    @GetMapping("/admin")
    String dashboard(@RequestParam(required = false) Long zoneId,
                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                     Model model) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<BranchService.BranchCard> cards = branchService.list(now, null);

        // 시도별 요약 (지점 수천 개를 한 표에 나열하지 않고 시도 단위로 묶는다)
        Map<String, int[]> bySido = new LinkedHashMap<>();
        for (BranchService.BranchCard c : cards) {
            int[] a = bySido.computeIfAbsent(c.sido(), k -> new int[4]);
            a[0]++;
            a[1] += c.totalSeats();
            if (c.openNow()) {
                a[2] += c.freeNow();
                a[3] += c.totalSeats();
            }
        }
        List<SidoRow> rows = new ArrayList<>();
        bySido.forEach((k, a) -> rows.add(new SidoRow(k, a[0], a[1], a[2], a[3] == 0 ? 0 : (int) Math.round((a[3] - a[2]) * 100.0 / a[3]))));
        rows.sort((x, y) -> y.branches() - x.branches());

        Zone zone = zoneId != null ? zones.findById(zoneId).orElse(null) : null;
        if (zone == null) {
            zone = zones.findByBranchIdOrderById(1L).get(0);
        }
        Branch branch = branches.findById(zone.getBranchId()).orElseThrow();
        model.addAttribute("sidoRows", rows);
        model.addAttribute("totalBranches", cards.size());
        model.addAttribute("totalSeats", cards.stream().mapToInt(BranchService.BranchCard::totalSeats).sum());
        model.addAttribute("zoneId", zone.getId());
        model.addAttribute("zoneName", zone.getName());
        model.addAttribute("branchId", branch.getId());
        model.addAttribute("branchName", branch.getName());
        model.addAttribute("sido", branch.getAddress().split(" ")[0]);
        model.addAttribute("date", date != null ? date : now.toLocalDate().minusDays(1));
        return "admin/dashboard";
    }

    @PostMapping("/admin/backfill")
    String backfill(RedirectAttributes ra) {
        OccupancyService.BackfillResult r = occupancy.backfill(LocalDateTime.now(clock));
        ra.addFlashAttribute("ok", "누락 통계 보충: 시간별 " + r.hourly() + "건, 일별 " + r.daily() + "건");
        return "redirect:/admin";
    }

    /** 좌석 관리: 지점이 많아 이름으로 검색해 한 지점씩 연다 */
    @GetMapping("/admin/seats")
    String seatList(@RequestParam(required = false) String q, @RequestParam(required = false) Long branchId, Model model) {
        LocalDateTime now = LocalDateTime.now(clock);
        String t = q == null ? "" : q.trim();
        List<BranchService.BranchCard> matches = branchService.list(now, null).stream()
                .filter(c -> !t.isEmpty() && (c.branch().getName().contains(t) || c.branch().getAddress().contains(t)))
                .limit(40).toList();
        Long bid = branchId != null ? branchId : (matches.isEmpty() ? 1L : matches.get(0).branch().getId());
        Branch b = branches.findById(bid).orElseThrow();
        List<SeatGroup> groups = new ArrayList<>();
        for (Zone z : zones.findByBranchIdOrderById(bid)) {
            groups.add(new SeatGroup(z.getName(), seats.findByZoneIdOrderByLabel(z.getId())));
        }
        model.addAttribute("q", t);
        model.addAttribute("matches", matches);
        model.addAttribute("branch", b);
        model.addAttribute("groups", groups);
        return "admin/seats";
    }

    @PostMapping("/admin/seats/{id}/toggle")
    @Transactional
    String toggleSeat(@PathVariable Long id, @RequestParam(required = false) Long branchId, RedirectAttributes ra) {
        seats.findById(id).ifPresent(Seat::toggleActive);
        ra.addFlashAttribute("ok", "좌석 상태를 바꿨습니다");
        return "redirect:/admin/seats" + (branchId == null ? "" : "?branchId=" + branchId);
    }

    @GetMapping("/admin/reservations")
    String reservationList(Model model) {
        List<ReservationViewService.View> rows = views.recent(LocalDateTime.now(clock));
        Map<Long, String> loginIds = members.findAllById(rows.stream().map(v -> v.r().getMemberId()).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Member::getId, Member::getLoginId));
        model.addAttribute("rows", rows);
        model.addAttribute("loginIds", loginIds);
        return "admin/reservations";
    }

    @PostMapping("/admin/reservations/{id}/cancel")
    String forceCancel(@PathVariable Long id, RedirectAttributes ra) {
        Flash.run(ra, "예약을 취소하고 포인트를 환불했습니다", () -> reservations.forceCancel(id, LocalDateTime.now(clock)));
        return "redirect:/admin/reservations";
    }

    @GetMapping("/admin/inquiries")
    String inquiryList(Model model) {
        var all = inquiries.all();
        Map<Long, String> loginIds = members.findAllById(all.stream().map(q -> q.getMemberId()).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Member::getId, Member::getLoginId));
        model.addAttribute("inquiries", all);
        model.addAttribute("loginIds", loginIds);
        return "admin/inquiries";
    }

    @PostMapping("/admin/inquiries/{id}/answer")
    String answer(@PathVariable Long id, @RequestParam String answer, RedirectAttributes ra) {
        Flash.run(ra, "답변을 등록했습니다", () -> inquiries.answer(id, answer, LocalDateTime.now(clock)));
        return "redirect:/admin/inquiries";
    }
}
