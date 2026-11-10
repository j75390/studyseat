package com.studyseat.service;

import com.studyseat.domain.Member;
import com.studyseat.repository.MemberRepository;
import java.time.LocalDateTime;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberService {

    public static final int WELCOME_POINT = 20_000;

    private final MemberRepository members;
    private final PasswordEncoder encoder;
    private final PointService points;

    public MemberService(MemberRepository members, PasswordEncoder encoder, PointService points) {
        this.members = members;
        this.encoder = encoder;
        this.points = points;
    }

    @Transactional
    public Member signup(String loginId, String rawPassword, String name, String phone, LocalDateTime now) {
        return signup(loginId, rawPassword, name, phone, null, now);
    }

    /** gender: F(여성) / M(남성) / null(입력 안 함). 성별 전용 좌석 예약에 쓰인다. */
    @Transactional
    public Member signup(String loginId, String rawPassword, String name, String phone, String gender, LocalDateTime now) {
        if (gender != null && !gender.equals("F") && !gender.equals("M")) {
            gender = null;
        }
        if (members.findByLoginId(loginId).isPresent()) {
            throw new IllegalArgumentException("이미 사용 중인 아이디입니다");
        }
        Member m = new Member(loginId, encoder.encode(rawPassword), name, phone, "MEMBER", now);
        m.setGender(gender);
        m = members.save(m);
        points.charge(m.getId(), WELCOME_POINT, now); // 가입 환영 포인트
        return m;
    }

    @Transactional
    public Member createAdmin(String loginId, String rawPassword, String name, LocalDateTime now) {
        return members.findByLoginId(loginId)
                .orElseGet(() -> members.save(new Member(loginId, encoder.encode(rawPassword), name, null, "ADMIN", now)));
    }

    @Transactional(readOnly = true)
    public Member getByLoginId(String loginId) {
        return members.findByLoginId(loginId).orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다"));
    }
}
