package com.studyseat.web;

import com.studyseat.service.InquiryService;
import com.studyseat.service.MemberService;
import com.studyseat.service.PointService;
import com.studyseat.service.ReservationService;
import com.studyseat.service.ReservationViewService;
import com.studyseat.service.ReviewService;
import java.security.Principal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 로그인한 회원의 화면: 내 예약·예약 상세(QR), 포인트·충전, 후기 작성, 1:1 문의. */
@Controller
public class MyController {

    private final ReservationService reservations;
    private final ReservationViewService views;
    private final MemberService memberService;
    private final PointService points;
    private final ReviewService reviews;
    private final InquiryService inquiries;
    private final Clock clock;

    public MyController(ReservationService reservations, ReservationViewService views, MemberService memberService,
                        PointService points, ReviewService reviews, InquiryService inquiries, Clock clock) {
        this.reservations = reservations;
        this.views = views;
        this.memberService = memberService;
        this.points = points;
        this.reviews = reviews;
        this.inquiries = inquiries;
        this.clock = clock;
    }

    private Long memberId(Principal p) {
        return memberService.getByLoginId(p.getName()).getId();
    }

    @GetMapping("/my/reservations")
    String myReservations(Principal principal, Model model) {
        Long memberId = memberId(principal);
        LocalDateTime now = LocalDateTime.now(clock);
        List<ReservationViewService.View> all = views.mine(memberId, now);
        model.addAttribute("current", all.stream().filter(ReservationViewService.View::inUse).toList());
        model.addAttribute("upcoming", all.stream().filter(v -> v.upcoming() && !v.inUse()).toList());
        model.addAttribute("past", all.stream().filter(v -> !v.upcoming() && !v.inUse()).toList());
        model.addAttribute("balance", points.balance(memberId));
        return "my-reservations";
    }

    @GetMapping("/reservations/{id}")
    String reservation(@PathVariable Long id, Principal principal, Model model) {
        ReservationViewService.View v = views.one(memberId(principal), id, LocalDateTime.now(clock));
        model.addAttribute("v", v);
        return "reservation";
    }

    @PostMapping("/reservations/{id}/cancel")
    String cancel(@PathVariable Long id, Principal principal, RedirectAttributes ra) {
        Long memberId = memberId(principal);
        Flash.run(ra, "예약을 취소했고 사용한 포인트를 돌려드렸습니다", () -> reservations.cancel(memberId, id, LocalDateTime.now(clock)));
        return "redirect:/my/reservations";
    }

    @PostMapping("/reservations/{id}/checkin")
    String checkIn(@PathVariable Long id, Principal principal, RedirectAttributes ra) {
        Long memberId = memberId(principal);
        Flash.run(ra, "입실했습니다. 이용 시간 동안 편하게 사용하세요", () -> reservations.checkIn(memberId, id, LocalDateTime.now(clock)));
        return "redirect:/reservations/" + id;
    }

    @PostMapping("/reservations/{id}/checkout")
    String checkOut(@PathVariable Long id, Principal principal, RedirectAttributes ra) {
        Long memberId = memberId(principal);
        Flash.run(ra, "퇴실했습니다. 이용 시간만큼 포인트가 적립되었어요", () -> reservations.checkOut(memberId, id, LocalDateTime.now(clock)));
        return "redirect:/my/reservations";
    }

    @PostMapping("/reservations/{id}/extend")
    String extend(@PathVariable Long id, @RequestParam int hours, Principal principal, RedirectAttributes ra) {
        Long memberId = memberId(principal);
        Flash.run(ra, hours + "시간 연장했습니다", () -> reservations.extend(memberId, id, hours, LocalDateTime.now(clock)));
        return "redirect:/reservations/" + id;
    }

    @GetMapping("/my/points")
    String myPoints(Principal principal, Model model) {
        Long memberId = memberId(principal);
        model.addAttribute("balance", points.balance(memberId));
        model.addAttribute("history", points.history(memberId));
        return "my-points";
    }

    /** 데모 충전: 실제 결제는 일어나지 않고 포인트만 적립된다. */
    @PostMapping("/my/points/charge")
    String charge(@RequestParam int amount, Principal principal, RedirectAttributes ra) {
        Long memberId = memberId(principal);
        Flash.run(ra, null, () -> {
            int bonus = points.chargePackage(memberId, amount, LocalDateTime.now(clock));
            ra.addFlashAttribute("ok", String.format("%,dP를 충전했어요%s (데모 결제)", amount,
                    bonus > 0 ? String.format(" · 보너스 %,dP", bonus) : ""));
        });
        return "redirect:/my/points";
    }

    @PostMapping("/reviews")
    String writeReview(@RequestParam Long reservationId, @RequestParam int rating, @RequestParam String content,
                       Principal principal, RedirectAttributes ra) {
        Long memberId = memberId(principal);
        Flash.run(ra, "후기를 남겼습니다", () -> reviews.write(memberId, reservationId, rating, content, LocalDateTime.now(clock)));
        return "redirect:/my/reservations";
    }

    @GetMapping("/my/inquiries")
    String myInquiries(Principal principal, Model model) {
        model.addAttribute("inquiries", inquiries.mine(memberId(principal)));
        return "my-inquiries";
    }

    @PostMapping("/my/inquiries")
    String writeInquiry(@RequestParam String title, @RequestParam String content, Principal principal,
                        RedirectAttributes ra) {
        Long memberId = memberId(principal);
        Flash.run(ra, "문의를 등록했습니다", () -> inquiries.create(memberId, title, content, LocalDateTime.now(clock)));
        return "redirect:/my/inquiries";
    }
}
