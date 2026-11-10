package com.studyseat.web;

import com.studyseat.service.ReservationService;
import com.studyseat.service.ReservationViewService;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 매장 입구 키오스크 시뮬레이터. 실제 매장에서는 QR 리더가 예약 코드를 읽어 보내는 것을 여기서는 코드 입력으로 대신한다.
 * (데모용: 코드만으로 입·퇴실이 되므로 실서비스에서는 단말 인증이 필요하다)
 */
@Controller
public class KioskController {

    private final ReservationService reservations;
    private final ReservationViewService views;
    private final Clock clock;

    public KioskController(ReservationService reservations, ReservationViewService views, Clock clock) {
        this.reservations = reservations;
        this.views = views;
        this.clock = clock;
    }

    @GetMapping("/kiosk")
    String kiosk() {
        return "kiosk";
    }

    @PostMapping("/kiosk/checkin")
    String checkIn(@RequestParam String code, RedirectAttributes ra) {
        Flash.run(ra, null, () -> {
            LocalDateTime now = LocalDateTime.now(clock);
            reservations.kioskCheckIn(code, now);
            ReservationViewService.View v = views.byCode(code, now);
            ra.addFlashAttribute("ok", "입실 완료 · " + v.place() + " · "
                    + v.r().getEndAt().toLocalTime() + "까지 이용하세요");
        });
        return "redirect:/kiosk";
    }

    @PostMapping("/kiosk/checkout")
    String checkOut(@RequestParam String code, RedirectAttributes ra) {
        Flash.run(ra, null, () -> {
            LocalDateTime now = LocalDateTime.now(clock);
            reservations.kioskCheckOut(code, now);
            ReservationViewService.View v = views.byCode(code, now);
            ra.addFlashAttribute("ok", "퇴실 완료 · " + v.place() + " · 이용해 주셔서 감사합니다");
        });
        return "redirect:/kiosk";
    }
}
