-- 지점·도면·내 지점·예약 코드 (스터디카페 앱처럼 지점을 지도에서 찾고 도면에서 좌석을 고르기 위한 확장)

CREATE TABLE branch (
    id            BIGINT        NOT NULL PRIMARY KEY,
    name          VARCHAR(30)   NOT NULL,
    address       VARCHAR(100)  NOT NULL,
    lat           DOUBLE        NOT NULL,
    lng           DOUBLE        NOT NULL,
    phone         VARCHAR(20),
    open_hour     INT           NOT NULL,          -- 0 이면 24시간 운영의 시작
    close_hour    INT           NOT NULL,          -- 24 이면 자정까지
    description   VARCHAR(300),
    amenities     VARCHAR(200),                    -- 쉼표로 구분
    plan_w        INT           NOT NULL,          -- 도면 가로(칸)
    plan_h        INT           NOT NULL           -- 도면 세로(칸)
);

ALTER TABLE zone ADD COLUMN branch_id BIGINT;
ALTER TABLE zone ADD COLUMN price_point_per_hr INT NOT NULL DEFAULT 2000;
ALTER TABLE zone ADD COLUMN area_x DOUBLE NOT NULL DEFAULT 0;
ALTER TABLE zone ADD COLUMN area_y DOUBLE NOT NULL DEFAULT 0;
ALTER TABLE zone ADD COLUMN area_w DOUBLE NOT NULL DEFAULT 0;
ALTER TABLE zone ADD COLUMN area_h DOUBLE NOT NULL DEFAULT 0;
-- 구역 이름은 지점 안에서만 유일하면 된다("일반석"이 지점마다 있다)
ALTER TABLE zone DROP CONSTRAINT uk_zone_name;
ALTER TABLE zone ADD CONSTRAINT uk_zone_branch_name UNIQUE (branch_id, name);

ALTER TABLE seat ADD COLUMN pos_x DOUBLE NOT NULL DEFAULT 0;
ALTER TABLE seat ADD COLUMN pos_y DOUBLE NOT NULL DEFAULT 0;
ALTER TABLE seat ADD COLUMN width DOUBLE NOT NULL DEFAULT 2;
ALTER TABLE seat ADD COLUMN height DOUBLE NOT NULL DEFAULT 2;
ALTER TABLE seat ADD COLUMN seat_type VARCHAR(10) NOT NULL DEFAULT 'DESK';   -- DESK / BOOTH / LOUNGE / ROOM
ALTER TABLE seat ADD COLUMN capacity INT NOT NULL DEFAULT 1;

-- 입구·카운터·창가 같은 도면 장식
CREATE TABLE plan_object (
    id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    branch_id     BIGINT        NOT NULL,
    kind          VARCHAR(12)   NOT NULL,
    name          VARCHAR(20)   NOT NULL,
    pos_x         DOUBLE        NOT NULL,
    pos_y         DOUBLE        NOT NULL,
    width         DOUBLE        NOT NULL,
    height        DOUBLE        NOT NULL,
    CONSTRAINT fk_po_branch FOREIGN KEY (branch_id) REFERENCES branch (id)
);

-- 내 지점(즐겨찾기)
CREATE TABLE favorite_branch (
    member_id     BIGINT        NOT NULL,
    branch_id     BIGINT        NOT NULL,
    PRIMARY KEY (member_id, branch_id),
    CONSTRAINT fk_fb_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_fb_branch FOREIGN KEY (branch_id) REFERENCES branch (id)
);

-- 입실용 예약 코드(QR·키오스크에 쓰는 6자리). 기존 행은 NULL이라 유니크가 겹치지 않는다.
ALTER TABLE reservation ADD COLUMN code VARCHAR(8);
CREATE UNIQUE INDEX uk_rsv_code ON reservation (code);

-- 가격 체계 변경: 구역마다 시간당 가격(원 단위 포인트), 적립은 이용 1시간당 100P
UPDATE cafe_policy SET earn_point_per_hr = 100, max_hours_per_rsv = 8;

-- ===== 강남점(1호점): 기존 구역 1·2에 도면 좌표를 채우고 프리미엄 부스를 추가 =====
INSERT INTO branch (id, name, address, lat, lng, phone, open_hour, close_hour, description, amenities, plan_w, plan_h) VALUES (1, '강남점', '서울 강남구 테헤란로 (예시 주소)', 37.4979, 127.0276, '02-555-0101', 9, 23, '역삼·강남역 직장인과 수험생이 많이 찾는 조용한 열람실 중심 지점입니다.', '무료 커피,무선충전,콘센트,사물함,프린터', 30, 18);
UPDATE zone SET name = '일반석', branch_id = 1, price_point_per_hr = 2000, area_x = 1, area_y = 3, area_w = 16, area_h = 7 WHERE id = 1;
UPDATE zone SET name = '스터디룸', branch_id = 1, price_point_per_hr = 8000, area_x = 19, area_y = 3, area_w = 10, area_h = 7 WHERE id = 2;
INSERT INTO zone (id, name, zone_type, branch_id, price_point_per_hr, area_x, area_y, area_w, area_h) VALUES (3, '프리미엄 부스', 'BOOTH', 1, 3000, 1, 11, 16, 6);
UPDATE seat SET pos_x = 1.67, pos_y = 3.73, width = 2.4, height = 2.4, seat_type = 'DESK', capacity = 1 WHERE zone_id = 1 AND label = 'A-01';
UPDATE seat SET pos_x = 4.73, pos_y = 3.73, width = 2.4, height = 2.4, seat_type = 'DESK', capacity = 1 WHERE zone_id = 1 AND label = 'A-02';
UPDATE seat SET pos_x = 7.8, pos_y = 3.73, width = 2.4, height = 2.4, seat_type = 'DESK', capacity = 1 WHERE zone_id = 1 AND label = 'A-03';
UPDATE seat SET pos_x = 10.87, pos_y = 3.73, width = 2.4, height = 2.4, seat_type = 'DESK', capacity = 1 WHERE zone_id = 1 AND label = 'A-04';
UPDATE seat SET pos_x = 13.93, pos_y = 3.73, width = 2.4, height = 2.4, seat_type = 'DESK', capacity = 1 WHERE zone_id = 1 AND label = 'A-05';
UPDATE seat SET pos_x = 1.67, pos_y = 6.87, width = 2.4, height = 2.4, seat_type = 'DESK', capacity = 1 WHERE zone_id = 1 AND label = 'A-06';
UPDATE seat SET pos_x = 4.73, pos_y = 6.87, width = 2.4, height = 2.4, seat_type = 'DESK', capacity = 1 WHERE zone_id = 1 AND label = 'A-07';
UPDATE seat SET pos_x = 7.8, pos_y = 6.87, width = 2.4, height = 2.4, seat_type = 'DESK', capacity = 1 WHERE zone_id = 1 AND label = 'A-08';
UPDATE seat SET pos_x = 10.87, pos_y = 6.87, width = 2.4, height = 2.4, seat_type = 'DESK', capacity = 1 WHERE zone_id = 1 AND label = 'A-09';
UPDATE seat SET pos_x = 13.93, pos_y = 6.87, width = 2.4, height = 2.4, seat_type = 'DESK', capacity = 1 WHERE zone_id = 1 AND label = 'A-10';
UPDATE seat SET pos_x = 19.27, pos_y = 3.33, width = 4.6, height = 3.0, seat_type = 'ROOM', capacity = 4 WHERE zone_id = 2 AND label = 'R-01';
UPDATE seat SET pos_x = 24.13, pos_y = 3.33, width = 4.6, height = 3.0, seat_type = 'ROOM', capacity = 4 WHERE zone_id = 2 AND label = 'R-02';
UPDATE seat SET pos_x = 19.27, pos_y = 6.67, width = 4.6, height = 3.0, seat_type = 'ROOM', capacity = 4 WHERE zone_id = 2 AND label = 'R-03';
UPDATE seat SET pos_x = 24.13, pos_y = 6.67, width = 4.6, height = 3.0, seat_type = 'ROOM', capacity = 4 WHERE zone_id = 2 AND label = 'R-04';
INSERT INTO seat (zone_id, label, pos_x, pos_y, width, height, seat_type, capacity) VALUES (3, 'B-01', 1.48, 11.4, 3.4, 2.4, 'BOOTH', 1);
INSERT INTO seat (zone_id, label, pos_x, pos_y, width, height, seat_type, capacity) VALUES (3, 'B-02', 5.36, 11.4, 3.4, 2.4, 'BOOTH', 1);
INSERT INTO seat (zone_id, label, pos_x, pos_y, width, height, seat_type, capacity) VALUES (3, 'B-03', 9.24, 11.4, 3.4, 2.4, 'BOOTH', 1);
INSERT INTO seat (zone_id, label, pos_x, pos_y, width, height, seat_type, capacity) VALUES (3, 'B-04', 13.12, 11.4, 3.4, 2.4, 'BOOTH', 1);
INSERT INTO seat (zone_id, label, pos_x, pos_y, width, height, seat_type, capacity) VALUES (3, 'B-05', 1.48, 14.2, 3.4, 2.4, 'BOOTH', 1);
INSERT INTO seat (zone_id, label, pos_x, pos_y, width, height, seat_type, capacity) VALUES (3, 'B-06', 5.36, 14.2, 3.4, 2.4, 'BOOTH', 1);
INSERT INTO seat (zone_id, label, pos_x, pos_y, width, height, seat_type, capacity) VALUES (3, 'B-07', 9.24, 14.2, 3.4, 2.4, 'BOOTH', 1);
INSERT INTO seat (zone_id, label, pos_x, pos_y, width, height, seat_type, capacity) VALUES (3, 'B-08', 13.12, 14.2, 3.4, 2.4, 'BOOTH', 1);
INSERT INTO plan_object (branch_id, kind, name, pos_x, pos_y, width, height) VALUES (1, 'WINDOW', '창가', 0, 0, 30, 1);
INSERT INTO plan_object (branch_id, kind, name, pos_x, pos_y, width, height) VALUES (1, 'ENTRANCE', '입구', 13, 17, 4, 1);
INSERT INTO plan_object (branch_id, kind, name, pos_x, pos_y, width, height) VALUES (1, 'COUNTER', '카운터', 19, 14, 6, 2);
INSERT INTO plan_object (branch_id, kind, name, pos_x, pos_y, width, height) VALUES (1, 'RESTROOM', '화장실', 26, 14, 3, 3);
