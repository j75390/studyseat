package com.studyseat.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전국 지점 시더. 실제로 존재하는 읍·면·동(최신 행정구역, {@code data/admin_areas.csv})마다 지점 수를 확률로 정해
 * 가상의 지점을 만든다. 지점 이름·주소·전화는 가상이고, "어느 읍·면·동에 있는가"만 실제 행정구역이다.
 * 도면은 {@link FloorPlanLayout}(바깥 벽·문이 있는 방·책상 섬·사물함·서비스바)이 배치한다.
 *
 * 분포 모델(실측이 아니라 모델): 대도시 동 > 일반 시 동 > 읍 > 면 순으로 기대 지점 수를 두어
 * 도심에 많고 읍·면에도 드물게 있도록 한다. 모든 시·군·구에는 최소 1곳. 표본 지역으로 지정한 대전은 더 촘촘하게 둔다.
 * (참고: 실제 전국 스터디카페 가맹점은 2024년 10월 기준 6,944곳 — KB국민카드 분석. 이 시더는 그 약 10% 규모.)
 */
@Service
public class NationwideSeeder {

    public static final long FIRST_BRANCH_ID = 1000;
    public static final long FIRST_ZONE_ID = 10_000;

    public record Area(String sido, String sgg, String dong, String kind, double lat, double lng) {
    }

    private static final Set<String> METRO = Set.of("서울", "부산", "대구", "인천", "대전", "울산", "전남광주");
    private static final String[] AMENITIES = {"무료 커피", "콘센트", "사물함", "프린터", "독서대", "무선충전", "모니터 대여", "빔프로젝터"};

    private final JdbcTemplate jdbc;

    public NationwideSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static List<Area> loadAreas() {
        List<Area> list = new ArrayList<>();
        try (InputStream in = new ClassPathResource("data/admin_areas.csv").getInputStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = r.readLine(); // 헤더
            while ((line = r.readLine()) != null) {
                String[] p = line.split(",");
                if (p.length != 6) {
                    throw new IllegalStateException("행정구역 CSV 형식 오류: " + line);
                }
                list.add(new Area(p[0], p[1], p[2], p[3], Double.parseDouble(p[4]), Double.parseDouble(p[5])));
            }
        } catch (IOException e) {
            throw new IllegalStateException("행정구역 데이터를 읽지 못했습니다", e);
        }
        return list;
    }

    /** 한 읍·면·동에 놓일 지점 수의 기대값 */
    public static double expected(Area a) {
        boolean urbanDistrict = METRO.contains(a.sido()) && a.sgg().endsWith("구");
        double base;
        if ("읍".equals(a.kind())) {
            base = 0.12;
        } else if ("면".equals(a.kind())) {
            base = 0.04;
        } else {
            base = urbanDistrict ? 0.3 : 0.18;
        }
        return "대전".equals(a.sido()) ? base * 1.8 : base; // 표본 지역: 대전은 더 촘촘하게
    }

    static int poisson(Random rnd, double lambda) {
        double l = Math.exp(-lambda);
        int k = 0;
        double p = 1;
        do {
            k++;
            p *= rnd.nextDouble();
        } while (p > l && k < 6);
        return k - 1;
    }

    /** 지점 수 계획만 계산한다(저장하지 않음) — 시험과 문서용 */
    public static List<Object[]> plan(List<Area> areas, long seed) {
        Random rnd = new Random(seed);
        List<Object[]> out = new ArrayList<>();
        Map<String, Integer> perSgg = new HashMap<>();
        for (Area a : areas) {
            int n = Math.min(4, poisson(rnd, expected(a)));
            for (int i = 0; i < n; i++) {
                out.add(new Object[] {a, i});
            }
            perSgg.merge(a.sido() + " " + a.sgg(), n, Integer::sum);
        }
        // 모든 시·군·구에는 최소 1곳(군청·시청 소재지 같은 중심지)을 둔다: 읍이 있으면 읍, 없으면 동, 그것도 없으면 면
        Map<String, List<Area>> bySgg = new LinkedHashMap<>();
        for (Area a : areas) {
            bySgg.computeIfAbsent(a.sido() + " " + a.sgg(), k -> new ArrayList<>()).add(a);
        }
        for (Map.Entry<String, List<Area>> e : bySgg.entrySet()) {
            if (perSgg.getOrDefault(e.getKey(), 0) > 0) {
                continue;
            }
            List<Area> pool = e.getValue().stream().filter(x -> "읍".equals(x.kind())).toList();
            if (pool.isEmpty()) {
                pool = e.getValue().stream().filter(x -> "동".equals(x.kind())).toList();
            }
            if (pool.isEmpty()) {
                pool = e.getValue();
            }
            out.add(new Object[] {pool.get(rnd.nextInt(pool.size())), 0});
        }
        return out;
    }

    /** 일반석(DESK) 번호 규칙(V5 마이그레이션과 같다): 8로 나눠 1·2 = 여성 전용, 8의 배수 = 남성 전용, 10으로 나눠 4 = 고정석 */
    public static String genderOf(String seatType, int number) {
        if (!"DESK".equals(seatType)) {
            return "A";
        }
        int m = number % 8;
        return m == 1 || m == 2 ? "F" : (m == 0 ? "M" : "A");
    }

    public static boolean fixedOf(String seatType, int number) {
        return "DESK".equals(seatType) && number % 10 == 4;
    }

    private static String phoneCode(String sido) {
        return switch (sido) {
            case "서울" -> "02";
            case "부산" -> "051";
            case "대구" -> "053";
            case "인천" -> "032";
            case "전남광주" -> "062";
            case "대전" -> "042";
            case "울산" -> "052";
            case "세종" -> "044";
            case "경기" -> "031";
            case "강원" -> "033";
            case "충북" -> "043";
            case "충남" -> "041";
            case "전북" -> "063";
            case "경북" -> "054";
            case "경남" -> "055";
            default -> "064";
        };
    }

    /** 전국 지점을 만들어 넣는다. 이미 만들었으면 건너뛴다. 만든 지점 수를 돌려준다. */
    @Transactional
    public int seed() {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM branch WHERE id >= ?", Integer.class, FIRST_BRANCH_ID);
        if (exists != null && exists > 0) {
            return 0;
        }
        List<Area> areas = loadAreas();
        List<Object[]> planned = plan(areas, 20261109L);
        Random rnd = new Random(7);

        List<Object[]> branches = new ArrayList<>();
        List<Object[]> zones = new ArrayList<>();
        List<Object[]> seats = new ArrayList<>();
        List<Object[]> objects = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        long branchId = FIRST_BRANCH_ID;
        long zoneId = FIRST_ZONE_ID;
        int phone = 0;

        for (Object[] pl : planned) {
            Area a = (Area) pl[0];
            int nth = (Integer) pl[1];
            boolean metroDong = METRO.contains(a.sido()) && a.sgg().endsWith("구") && "동".equals(a.kind());
            boolean town = "동".equals(a.kind()) && !metroDong;

            String baseName = a.sido() + " " + a.sgg() + " " + a.dong() + "점";
            String name = nth == 0 ? baseName : baseName.replace("점", "") + (nth + 1) + "호점";
            if (!usedNames.add(name)) {
                name = name + "(" + (usedNames.size() % 97) + ")";
            }
            // 24시간 비율: 대도시 동 35%, 일반 시 20%, 읍·면 5%
            double r = rnd.nextDouble();
            int open;
            int close;
            if (r < (metroDong ? 0.35 : town ? 0.20 : 0.05)) {
                open = 0;
                close = 24;
            } else {
                int[][] hours = metroDong ? new int[][] {{8, 24}, {9, 23}, {7, 23}, {9, 24}} : new int[][] {{9, 22}, {9, 23}, {10, 22}, {8, 22}};
                int[] h = hours[rnd.nextInt(hours.length)];
                open = h[0];
                close = h[1];
            }

            // 규모: 대도시는 크게, 읍·면은 작게. 스타일(홀형/도서관형, 좌우 거울)은 무작위
            int style = rnd.nextInt(4);
            int main;
            int rooms;
            boolean lounge = rnd.nextInt(3) == 0;
            int second;
            if (metroDong) {
                main = 14 + rnd.nextInt(11);
                second = 4 + rnd.nextInt(5);
                rooms = 2 + rnd.nextInt(3);
            } else if (town) {
                main = 10 + rnd.nextInt(9);
                second = 3 + rnd.nextInt(5);
                rooms = 1 + rnd.nextInt(3);
            } else {
                main = 6 + rnd.nextInt(5);
                second = 2 + rnd.nextInt(3);
                rooms = 1 + rnd.nextInt(2);
            }
            main = Math.min(main, FloorPlanLayout.maxDesks(style));
            int loungeCount = lounge ? Math.min(second, FloorPlanLayout.MAX_LOUNGE) : 0;
            int boothCount = lounge ? 0 : Math.min(second, FloorPlanLayout.MAX_BOOTH);
            rooms = Math.min(rooms, FloorPlanLayout.MAX_ROOM);
            FloorPlanLayout.Layout layout = FloorPlanLayout.arrange(style, main, boothCount, loungeCount, rooms);

            // 가격: 수도권 > 대도시 > 그 외
            double tier = ("서울".equals(a.sido()) || "경기".equals(a.sido()) || "인천".equals(a.sido())) ? 1.15 : (METRO.contains(a.sido()) ? 1.0 : 0.88);
            int priceDesk = roundTo100(1800 * tier + rnd.nextInt(5) * 100);
            int priceSecond = roundTo100(priceDesk * (lounge ? 1.8 : 1.55));
            int priceRoom = roundTo100(priceDesk * 3.6);

            double lat = a.lat() + (nth == 0 ? 0 : (rnd.nextDouble() - 0.5) * 0.008);
            double lng = a.lng() + (nth == 0 ? 0 : (rnd.nextDouble() - 0.5) * 0.008);
            StringBuilder amen = new StringBuilder();
            if (open == 0 && close == 24) {
                amen.append("24시간,");
            }
            amen.append(AMENITIES[0]).append(',').append(AMENITIES[1]).append(',').append(AMENITIES[2 + rnd.nextInt(AMENITIES.length - 2)]);
            String desc = a.dong() + " 인근 지점입니다." + (open == 0 && close == 24 ? " 24시간 운영합니다." : "");
            branches.add(new Object[] {branchId, name, a.sido() + " " + a.sgg() + " " + a.dong() + " (예시 주소)", lat, lng,
                    String.format(Locale.ROOT, "%s-555-%04d", phoneCode(a.sido()), phone++ % 10000), open, close, desc, amen.toString(),
                    FloorPlanLayout.W, FloorPlanLayout.H, 2});

            // 구역과 좌석: 일반석(READING) + 부스 또는 라운지 + 스터디룸(ROOM)
            long deskZone = zoneId++;
            zones.add(new Object[] {deskZone, "일반석", "READING", branchId, priceDesk, layout.deskZone().x(), layout.deskZone().y(),
                    layout.deskZone().w(), layout.deskZone().h()});
            addSeats(seats, deskZone, "A", "DESK", 1, layout.desks());
            if (lounge) {
                long z = zoneId++;
                zones.add(new Object[] {z, "라운지", "LOUNGE", branchId, priceSecond, layout.loungeZone().x(), layout.loungeZone().y(),
                        layout.loungeZone().w(), layout.loungeZone().h()});
                addSeats(seats, z, "L", "LOUNGE", 1, layout.lounges());
            } else {
                long z = zoneId++;
                zones.add(new Object[] {z, "프리미엄 부스", "BOOTH", branchId, priceSecond, layout.boothZone().x(), layout.boothZone().y(),
                        layout.boothZone().w(), layout.boothZone().h()});
                addSeats(seats, z, "B", "BOOTH", 1, layout.booths());
            }
            long roomZone = zoneId++;
            zones.add(new Object[] {roomZone, "스터디룸", "ROOM", branchId, priceRoom, layout.roomZone().x(), layout.roomZone().y(),
                    layout.roomZone().w(), layout.roomZone().h()});
            addSeats(seats, roomZone, "R", "ROOM", 4, layout.rooms());

            for (FloorPlanLayout.Obj o : layout.objects()) {
                objects.add(new Object[] {branchId, o.kind(), o.name(), o.rect().x(), o.rect().y(), o.rect().w(), o.rect().h()});
            }
            branchId++;
        }

        jdbc.batchUpdate("INSERT INTO branch (id, name, address, lat, lng, phone, open_hour, close_hour, description, amenities, plan_w, plan_h, layout_ver) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", branches);
        jdbc.batchUpdate("INSERT INTO zone (id, name, zone_type, branch_id, price_point_per_hr, area_x, area_y, area_w, area_h) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", zones);
        jdbc.batchUpdate("INSERT INTO seat (zone_id, label, pos_x, pos_y, width, height, seat_type, capacity, gender, fixed_seat) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", seats);
        jdbc.batchUpdate("INSERT INTO plan_object (branch_id, kind, name, pos_x, pos_y, width, height) VALUES (?, ?, ?, ?, ?, ?, ?)", objects);
        return branches.size();
    }

    private static void addSeats(List<Object[]> seats, long zoneId, String prefix, String seatType, int capacity,
                                 List<FloorPlanLayout.Rect> positions) {
        int i = 1;
        for (FloorPlanLayout.Rect p : positions) {
            seats.add(new Object[] {zoneId, String.format("%s-%02d", prefix, i), p.x(), p.y(), p.w(), p.h(), seatType, capacity,
                    genderOf(seatType, i), fixedOf(seatType, i)});
            i++;
        }
    }

    private static int roundTo100(double v) {
        return (int) (Math.round(v / 100.0) * 100);
    }
}
