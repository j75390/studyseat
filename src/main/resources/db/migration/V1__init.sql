-- StudySeat 초기 스키마 (MySQL 8 / H2 MySQL 모드 공용)

CREATE TABLE member (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    login_id      VARCHAR(30)  NOT NULL,
    password      VARCHAR(100) NOT NULL,
    name          VARCHAR(30)  NOT NULL,
    phone         VARCHAR(20),
    role          VARCHAR(10)  NOT NULL DEFAULT 'MEMBER',
    created_at    DATETIME     NOT NULL,
    CONSTRAINT uk_member_login UNIQUE (login_id)
);

CREATE TABLE zone (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(30)  NOT NULL,
    zone_type     VARCHAR(10)  NOT NULL,          -- READING / ROOM
    CONSTRAINT uk_zone_name UNIQUE (name)
);

CREATE TABLE seat (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    zone_id       BIGINT       NOT NULL,
    label         VARCHAR(10)  NOT NULL,          -- A-01
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uk_seat_label UNIQUE (zone_id, label),
    CONSTRAINT fk_seat_zone FOREIGN KEY (zone_id) REFERENCES zone (id)
);

CREATE TABLE cafe_policy (
    id                 BIGINT  NOT NULL PRIMARY KEY,
    open_hour          INT     NOT NULL,          -- 9
    close_hour         INT     NOT NULL,          -- 23
    max_hours_per_rsv  INT     NOT NULL,          -- 한 번에 최대 4시간
    cancel_before_min  INT     NOT NULL,          -- 시작 N분 전까지 취소 가능
    noshow_grace_min   INT     NOT NULL,          -- 시작 후 N분 내 체크인 없으면 노쇼
    earn_point_per_hr  INT     NOT NULL,          -- 이용 1시간당 적립
    price_point_per_hr INT     NOT NULL           -- 예약 1시간당 사용 포인트
);

CREATE TABLE reservation (
    id              BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id       BIGINT      NOT NULL,
    seat_id         BIGINT      NOT NULL,
    start_at        DATETIME    NOT NULL,
    end_at          DATETIME    NOT NULL,
    status          VARCHAR(12) NOT NULL,         -- BOOKED / CHECKED_IN / DONE / CANCELED / NO_SHOW
    used_point      INT         NOT NULL DEFAULT 0,
    checked_in_at   DATETIME,
    checked_out_at  DATETIME,
    created_at      DATETIME    NOT NULL,
    CONSTRAINT fk_rsv_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_rsv_seat   FOREIGN KEY (seat_id)   REFERENCES seat (id)
);
CREATE INDEX ix_rsv_member ON reservation (member_id, start_at);
CREATE INDEX ix_rsv_seat_time ON reservation (seat_id, start_at, end_at, status);

-- 예약 1건을 1시간 단위로 쪼갠 행. (seat_id, slot_start) 유니크가 이중 예약을 DB에서 막는다.
-- 취소/노쇼 시 이 행들을 삭제해 자리를 다시 연다.
CREATE TABLE reservation_slot (
    id              BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    reservation_id  BIGINT      NOT NULL,
    seat_id         BIGINT      NOT NULL,
    slot_start      DATETIME    NOT NULL,
    CONSTRAINT uk_slot UNIQUE (seat_id, slot_start),
    CONSTRAINT fk_slot_rsv FOREIGN KEY (reservation_id) REFERENCES reservation (id)
);

-- 포인트 원장: 잔액 = SUM(amount). 수정·삭제 없이 쌓기만 한다.
CREATE TABLE point_ledger (
    id              BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id       BIGINT      NOT NULL,
    amount          INT         NOT NULL,         -- 적립 +, 사용 -
    reason          VARCHAR(20) NOT NULL,         -- CHARGE / USE / REFUND / EARN / PENALTY
    reservation_id  BIGINT,
    created_at      DATETIME    NOT NULL,
    CONSTRAINT fk_pl_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_pl_rsv FOREIGN KEY (reservation_id) REFERENCES reservation (id)
);
-- 같은 예약에 같은 사유가 두 번 기록되지 않게 (환불·적립 중복 방지)
CREATE UNIQUE INDEX uk_pl_once ON point_ledger (reservation_id, reason);
CREATE INDEX ix_pl_member ON point_ledger (member_id, created_at);

CREATE TABLE review (
    id              BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    reservation_id  BIGINT      NOT NULL,
    member_id       BIGINT      NOT NULL,
    rating          INT         NOT NULL,
    content         VARCHAR(500) NOT NULL,
    created_at      DATETIME    NOT NULL,
    CONSTRAINT uk_review_rsv UNIQUE (reservation_id),
    CONSTRAINT fk_rv_rsv FOREIGN KEY (reservation_id) REFERENCES reservation (id),
    CONSTRAINT fk_rv_member FOREIGN KEY (member_id) REFERENCES member (id)
);

CREATE TABLE inquiry (
    id              BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id       BIGINT      NOT NULL,
    title           VARCHAR(100) NOT NULL,
    content         VARCHAR(1000) NOT NULL,
    answer          VARCHAR(1000),
    answered_at     DATETIME,
    created_at      DATETIME    NOT NULL,
    CONSTRAINT fk_iq_member FOREIGN KEY (member_id) REFERENCES member (id)
);

-- 혼잡도 통계: 시간별 → 일별. 같은 구간을 다시 계산해도 한 행만 남도록 PK로 UPSERT.
CREATE TABLE occupancy_hourly (
    zone_id         BIGINT      NOT NULL,
    stat_hour       DATETIME    NOT NULL,
    occupied        INT         NOT NULL,         -- 그 시간에 예약된 좌석 수
    capacity        INT         NOT NULL,         -- 그 시간의 전체 좌석 수
    PRIMARY KEY (zone_id, stat_hour),
    CONSTRAINT fk_oh_zone FOREIGN KEY (zone_id) REFERENCES zone (id)
);

CREATE TABLE occupancy_daily (
    zone_id         BIGINT      NOT NULL,
    stat_date       DATE        NOT NULL,
    avg_rate        DECIMAL(5,2) NOT NULL,        -- 평균 점유율(%)
    peak_rate       DECIMAL(5,2) NOT NULL,
    peak_hour       INT         NOT NULL,
    PRIMARY KEY (zone_id, stat_date),
    CONSTRAINT fk_od_zone FOREIGN KEY (zone_id) REFERENCES zone (id)
);
