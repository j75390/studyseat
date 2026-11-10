package com.studyseat.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시간별·일별 혼잡도(좌석 예약 점유율) 집계.
 * - 시간별: 그 시간에 예약(슬롯)이 잡힌 좌석 수 / 사용 가능한 좌석 수 (구역 단위)
 * - 일별: 그날 시간별 점유율의 평균·최대와 가장 붐빈 시각
 *
 * 지점이 수천 개·구역이 만 개 가까이 되므로 구역마다 쿼리하지 않고 SQL 한 문장으로 한꺼번에 집계한다.
 * 이미 있는 (구역, 시각) 행은 건드리지 않으므로(NOT EXISTS) 다시 돌려도 행이 늘지 않는다.
 * 앱이 꺼져 있던 구간은 시작할 때 backfill로 채운다(시간별 먼저, 일별은 그다음).
 */
@Service
public class OccupancyService {

    public record Hourly(LocalDateTime hour, int occupied, int capacity) {
        public double rate() {
            return capacity == 0 ? 0 : occupied * 100.0 / capacity;
        }
    }

    public record Daily(LocalDate date, double avgRate, double peakRate, int peakHour) {
    }

    public record BackfillResult(int hourly, int daily) {
    }

    private final JdbcTemplate jdbc;

    public OccupancyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 그 시간에 영업 중인 지점의 모든 구역 중 아직 통계가 없는 구역을 한 번에 집계한다. 채운 행 수를 돌려준다. */
    @Transactional
    public int fillHourly(LocalDateTime hourStart) {
        return jdbc.update(
                "INSERT INTO occupancy_hourly (zone_id, stat_hour, occupied, capacity) "
                        + "SELECT z.id, ?, "
                        + "  (SELECT COUNT(*) FROM reservation_slot rs JOIN seat s ON s.id = rs.seat_id "
                        + "    WHERE s.zone_id = z.id AND rs.slot_start = ?), "
                        + "  (SELECT COUNT(*) FROM seat s2 WHERE s2.zone_id = z.id AND s2.active = TRUE) "
                        + "FROM zone z JOIN branch b ON b.id = z.branch_id "
                        + "WHERE b.open_hour <= ? AND ? < b.close_hour "
                        + "  AND NOT EXISTS (SELECT 1 FROM occupancy_hourly h WHERE h.zone_id = z.id AND h.stat_hour = ?)",
                hourStart, hourStart, hourStart.getHour(), hourStart.getHour(), hourStart);
    }

    /**
     * 시간별 통계가 운영 시간 전체 있는데 일별이 없는 (구역, 날짜)를 한 번에 일별로 만든다.
     * 일부 시간만으로 평균을 내면 부정확하므로 하루치가 다 있을 때만 만든다.
     */
    @Transactional
    public int fillDaily(LocalDate date) {
        LocalDateTime from = date.atStartOfDay();
        LocalDateTime to = date.plusDays(1).atStartOfDay();
        return jdbc.update(
                "INSERT INTO occupancy_daily (zone_id, stat_date, avg_rate, peak_rate, peak_hour) "
                        + "SELECT g.zone_id, ?, g.avg_rate, g.peak_rate, "
                        + "  (SELECT EXTRACT(HOUR FROM h2.stat_hour) FROM occupancy_hourly h2 "
                        + "    WHERE h2.zone_id = g.zone_id AND h2.stat_hour >= ? AND h2.stat_hour < ? AND h2.capacity > 0 "
                        + "    ORDER BY h2.occupied * 1.0 / h2.capacity DESC, h2.stat_hour LIMIT 1) "
                        + "FROM (SELECT h.zone_id AS zone_id, "
                        + "        ROUND(AVG(h.occupied * 100.0 / h.capacity), 2) AS avg_rate, "
                        + "        ROUND(MAX(h.occupied * 100.0 / h.capacity), 2) AS peak_rate, COUNT(*) AS n "
                        + "      FROM occupancy_hourly h "
                        + "      WHERE h.stat_hour >= ? AND h.stat_hour < ? AND h.capacity > 0 GROUP BY h.zone_id) g "
                        + "JOIN zone z ON z.id = g.zone_id JOIN branch b ON b.id = z.branch_id "
                        + "WHERE g.n = b.close_hour - b.open_hour "
                        + "  AND NOT EXISTS (SELECT 1 FROM occupancy_daily d WHERE d.zone_id = g.zone_id AND d.stat_date = ?)",
                date, from, to, from, to, date);
    }

    /** 방금 끝난 시간을 집계한다(스케줄러가 매시 호출). 같은 시간을 다시 계산하도록 기존 행을 지우고 채운다. */
    @Transactional
    public void aggregatePreviousHour(LocalDateTime now) {
        LocalDateTime prev = now.withMinute(0).withSecond(0).withNano(0).minusHours(1);
        jdbc.update("DELETE FROM occupancy_hourly WHERE stat_hour = ?", prev);
        fillHourly(prev);
        fillDaily(prev.toLocalDate()); // 그날 운영 시간이 모두 채워진 구역만 일별이 만들어진다
    }

    /**
     * 누락 보충: 최근 48시간 중 시간별 통계가 비어 있는 운영 시간을 채우고,
     * 그다음 최근 7일 중 일별 통계가 비어 있는 날을 채운다. 이미 있는 구간은 건드리지 않는다.
     * 운영 시간은 구역이 속한 지점 기준이다(24시간 지점은 하루 24칸).
     */
    @Transactional
    public BackfillResult backfill(LocalDateTime now) {
        LocalDateTime thisHour = now.withMinute(0).withSecond(0).withNano(0);
        int hourlyFilled = 0;
        for (LocalDateTime h = thisHour.minusHours(48); h.isBefore(thisHour); h = h.plusHours(1)) {
            hourlyFilled += fillHourly(h);
        }
        int dailyFilled = 0;
        LocalDate today = now.toLocalDate();
        for (LocalDate d = today.minusDays(7); d.isBefore(today); d = d.plusDays(1)) {
            dailyFilled += fillDaily(d);
        }
        return new BackfillResult(hourlyFilled, dailyFilled);
    }

    @Transactional(readOnly = true)
    public List<Hourly> hourly(Long zoneId, LocalDate date) {
        return jdbc.query(
                "SELECT stat_hour, occupied, capacity FROM occupancy_hourly "
                        + "WHERE zone_id = ? AND stat_hour >= ? AND stat_hour < ? ORDER BY stat_hour",
                (rs, i) -> new Hourly(rs.getObject(1, LocalDateTime.class), rs.getInt(2), rs.getInt(3)),
                zoneId, date.atStartOfDay(), date.plusDays(1).atStartOfDay());
    }

    @Transactional(readOnly = true)
    public List<Daily> daily(Long zoneId, LocalDate from, LocalDate to) {
        return jdbc.query(
                "SELECT stat_date, avg_rate, peak_rate, peak_hour FROM occupancy_daily "
                        + "WHERE zone_id = ? AND stat_date >= ? AND stat_date <= ? ORDER BY stat_date",
                (rs, i) -> new Daily(rs.getObject(1, LocalDate.class), rs.getDouble(2), rs.getDouble(3), rs.getInt(4)),
                zoneId, from, to);
    }
}
