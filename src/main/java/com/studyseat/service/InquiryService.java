package com.studyseat.service;

import com.studyseat.domain.Inquiry;
import com.studyseat.repository.InquiryRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InquiryService {

    private final InquiryRepository inquiries;

    public InquiryService(InquiryRepository inquiries) {
        this.inquiries = inquiries;
    }

    @Transactional
    public Inquiry create(Long memberId, String title, String content, LocalDateTime now) {
        if (title == null || title.isBlank() || title.length() > 100) {
            throw new IllegalArgumentException("제목은 1~100자로 써 주세요");
        }
        if (content == null || content.isBlank() || content.length() > 1000) {
            throw new IllegalArgumentException("내용은 1~1000자로 써 주세요");
        }
        return inquiries.save(new Inquiry(memberId, title.trim(), content.trim(), now));
    }

    @Transactional
    public void answer(Long inquiryId, String answer, LocalDateTime now) {
        if (answer == null || answer.isBlank() || answer.length() > 1000) {
            throw new IllegalArgumentException("답변은 1~1000자로 써 주세요");
        }
        inquiries.findById(inquiryId).orElseThrow(() -> new IllegalArgumentException("문의를 찾을 수 없습니다"))
                .answer(answer.trim(), now);
    }

    @Transactional(readOnly = true)
    public List<Inquiry> mine(Long memberId) {
        return inquiries.findByMemberIdOrderByIdDesc(memberId);
    }

    @Transactional(readOnly = true)
    public List<Inquiry> all() {
        return inquiries.findAllByOrderByIdDesc();
    }
}
