package com.studyseat.web;

import com.studyseat.service.InsufficientPointException;
import com.studyseat.service.SeatTakenException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 화면 요청에서 서비스 규칙 위반을 사용자에게 보여 줄 메시지로 바꾼다. */
final class Flash {

    private Flash() {
    }

    static void run(RedirectAttributes ra, String okMessage, Runnable action) {
        try {
            action.run();
            if (okMessage != null) {
                ra.addFlashAttribute("ok", okMessage);
            }
        } catch (SeatTakenException | InsufficientPointException | IllegalArgumentException
                 | IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
    }
}
