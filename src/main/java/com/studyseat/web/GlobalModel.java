package com.studyseat.web;

import java.security.Principal;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** 모든 화면의 상단 메뉴에서 쓰는 로그인 정보. */
@ControllerAdvice
public class GlobalModel {

    @ModelAttribute("me")
    String me(Principal principal) {
        return principal == null ? null : principal.getName();
    }

    @ModelAttribute("isAdmin")
    boolean isAdmin(Principal principal) {
        return principal instanceof Authentication a
                && a.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals("ROLE_ADMIN"));
    }
}
