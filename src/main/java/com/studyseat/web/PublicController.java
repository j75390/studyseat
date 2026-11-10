package com.studyseat.web;

import com.studyseat.service.MemberService;
import com.studyseat.service.ReviewService;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 로그인 없이 볼 수 있는 화면: 로그인, 회원가입, 후기 목록. */
@Controller
public class PublicController {

    private final MemberService memberService;
    private final ReviewService reviewService;
    private final Clock clock;

    public PublicController(MemberService memberService, ReviewService reviewService, Clock clock) {
        this.memberService = memberService;
        this.reviewService = reviewService;
        this.clock = clock;
    }

    @GetMapping("/login")
    String login() {
        return "login";
    }

    @GetMapping("/signup")
    String signupForm() {
        return "signup";
    }

    @PostMapping("/signup")
    String signup(@RequestParam String loginId, @RequestParam String password, @RequestParam String name,
                  @RequestParam(required = false) String phone,
                  @RequestParam(required = false) String gender, RedirectAttributes ra) {
        if (!loginId.matches("[a-zA-Z0-9_]{4,20}")) {
            ra.addFlashAttribute("error", "아이디는 영문·숫자·밑줄 4~20자입니다");
            return "redirect:/signup";
        }
        if (password.length() < 8 || password.length() > 50) {
            ra.addFlashAttribute("error", "비밀번호는 8자 이상이어야 합니다");
            return "redirect:/signup";
        }
        if (name.isBlank() || name.length() > 20) {
            ra.addFlashAttribute("error", "이름은 1~20자로 입력해 주세요");
            return "redirect:/signup";
        }
        try {
            memberService.signup(loginId, password, name.trim(), phone, gender == null || gender.isBlank() ? null : gender,
                    LocalDateTime.now(clock));
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/signup";
        }
        ra.addFlashAttribute("ok", "가입이 완료되었습니다. 환영 포인트 20,000P가 지급되었어요. 로그인해 주세요.");
        return "redirect:/login";
    }

    @GetMapping("/reviews")
    String reviews(Model model) {
        model.addAttribute("reviews", reviewService.list(null));
        model.addAttribute("rating", reviewService.averageRating(null));
        return "reviews";
    }
}
