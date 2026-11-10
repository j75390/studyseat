-- 운영 정책 (한 줄)
INSERT INTO cafe_policy (id, open_hour, close_hour, max_hours_per_rsv, cancel_before_min, noshow_grace_min, earn_point_per_hr, price_point_per_hr)
VALUES (1, 9, 23, 4, 60, 10, 10, 100);

-- 구역: 열람실 A, 스터디룸 R
INSERT INTO zone (id, name, zone_type) VALUES (1, '열람실 A', 'READING');
INSERT INTO zone (id, name, zone_type) VALUES (2, '스터디룸', 'ROOM');

INSERT INTO seat (zone_id, label) VALUES (1, 'A-01');
INSERT INTO seat (zone_id, label) VALUES (1, 'A-02');
INSERT INTO seat (zone_id, label) VALUES (1, 'A-03');
INSERT INTO seat (zone_id, label) VALUES (1, 'A-04');
INSERT INTO seat (zone_id, label) VALUES (1, 'A-05');
INSERT INTO seat (zone_id, label) VALUES (1, 'A-06');
INSERT INTO seat (zone_id, label) VALUES (1, 'A-07');
INSERT INTO seat (zone_id, label) VALUES (1, 'A-08');
INSERT INTO seat (zone_id, label) VALUES (1, 'A-09');
INSERT INTO seat (zone_id, label) VALUES (1, 'A-10');
INSERT INTO seat (zone_id, label) VALUES (2, 'R-01');
INSERT INTO seat (zone_id, label) VALUES (2, 'R-02');
INSERT INTO seat (zone_id, label) VALUES (2, 'R-03');
INSERT INTO seat (zone_id, label) VALUES (2, 'R-04');
