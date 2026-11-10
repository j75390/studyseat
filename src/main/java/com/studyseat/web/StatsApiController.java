package com.studyseat.web;

import com.studyseat.domain.Zone;
import com.studyseat.repository.ZoneRepository;
import com.studyseat.service.BranchService;
import com.studyseat.service.MemberService;
import com.studyseat.service.OccupancyService;
import java.security.Principal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 지도와 차트가 쓰는 JSON API (읽기 전용, 로그인 불필요). */
@RestController
public class StatsApiController {

    private final OccupancyService occupancy;
    private final BranchService branchService;
    private final MemberService memberService;
    private final ZoneRepository zones;
    private final Clock clock;

    public StatsApiController(OccupancyService occupancy, BranchService branchService, MemberService memberService,
                              ZoneRepository zones, Clock clock) {
        this.occupancy = occupancy;
        this.branchService = branchService;
        this.memberService = memberService;
        this.zones = zones;
        this.clock = clock;
    }

    @GetMapping("/api/stats/hourly")
    List<Map<String, Object>> hourly(@RequestParam Long zoneId,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return occupancy.hourly(zoneId, date).stream().map(h -> Map.<String, Object>of(
                "hour", h.hour().getHour(),
                "occupied", h.occupied(),
                "capacity", h.capacity(),
                "rate", Math.round(h.rate() * 10) / 10.0)).toList();
    }

    @GetMapping("/api/stats/daily")
    List<Map<String, Object>> daily(@RequestParam Long zoneId, @RequestParam(defaultValue = "7") int days) {
        LocalDate today = LocalDate.now(clock);
        int d = Math.max(1, Math.min(days, 31));
        return occupancy.daily(zoneId, today.minusDays(d), today.minusDays(1)).stream().map(x -> Map.<String, Object>of(
                "date", x.date().toString(),
                "avgRate", x.avgRate(),
                "peakRate", x.peakRate(),
                "peakHour", x.peakHour())).toList();
    }

    /**
     * 지도·목록에 쓰는 지점 목록(잔여석·혼잡 단계 포함). 지점이 수천 개라 값은 짧은 키로 내려 보낸다.
     * 로그인했다면 내 지점(fav)도 표시한다. sido(시·도 약칭)로 거를 수 있다.
     */
    @GetMapping("/api/branches")
    List<Map<String, Object>> branches(@RequestParam(required = false) String sido, Principal principal) {
        LocalDateTime now = LocalDateTime.now(clock);
        Long memberId = principal == null ? null : memberService.getByLoginId(principal.getName()).getId();
        List<Map<String, Object>> out = new ArrayList<>();
        for (BranchService.BranchCard c : branchService.list(now, memberId)) {
            if (sido != null && !sido.isBlank() && !c.sido().equals(sido)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.branch().getId());
            m.put("name", c.branch().getName());
            m.put("address", c.branch().getAddress());
            m.put("sido", c.sido());
            m.put("sgg", c.sgg());
            m.put("lat", c.branch().getLat());
            m.put("lng", c.branch().getLng());
            m.put("openNow", c.openNow());
            m.put("freeSeats", c.freeNow());
            m.put("totalSeats", c.totalSeats());
            m.put("busyPercent", c.busyPercent());
            m.put("level", c.level());
            m.put("minPricePerHour", c.minPrice());
            m.put("open", c.branch().getOpenHour());
            m.put("close", c.branch().getCloseHour());
            m.put("favorite", c.favorite());
            out.add(m);
        }
        return out;
    }

    /** 한 지점의 구역 목록(관리자 대시보드의 구역 선택용) */
    @GetMapping("/api/branches/{id}/zones")
    List<Map<String, Object>> branchZones(@PathVariable Long id) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Zone z : zones.findByBranchIdOrderById(id)) {
            out.add(Map.of("id", z.getId(), "name", z.getName(), "price", z.getPricePointPerHr()));
        }
        return out;
    }
}
