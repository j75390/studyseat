-- 실제 독서실·스터디카페 좌석 선택 화면처럼: 성별 전용 좌석, 고정석, 회원 성별, 도면 시설(키오스크·사물함)

-- 좌석 성별 구분: A(누구나) / F(여성 전용) / M(남성 전용)
ALTER TABLE seat ADD COLUMN gender VARCHAR(1) NOT NULL DEFAULT 'A';
-- 고정석: 월 이용권 회원이 쓰는 자리라 시간권으로는 예약할 수 없다
ALTER TABLE seat ADD COLUMN fixed_seat BOOLEAN NOT NULL DEFAULT FALSE;
-- 회원 성별(선택 입력). 성별 전용 좌석은 해당 성별로 등록한 회원만 예약할 수 있다
ALTER TABLE member ADD COLUMN gender VARCHAR(1);

-- 일반석(DESK)에 규칙적으로 배정: 번호가 8로 나눠 1·2이면 여성 전용, 8의 배수이면 남성 전용, 10으로 나눠 4이면 고정석
-- (앱이 런타임에 만드는 전국 지점도 같은 규칙을 쓴다: NationwideSeeder.assign)
UPDATE seat SET gender = 'F'
 WHERE seat_type = 'DESK' AND MOD(CAST(SUBSTRING(label, 3) AS INT), 8) IN (1, 2);
UPDATE seat SET gender = 'M'
 WHERE seat_type = 'DESK' AND MOD(CAST(SUBSTRING(label, 3) AS INT), 8) = 0;
UPDATE seat SET fixed_seat = TRUE
 WHERE seat_type = 'DESK' AND MOD(CAST(SUBSTRING(label, 3) AS INT), 10) = 4;

-- 도면 시설: 모든 지점 입구 옆 키오스크, 사물함
INSERT INTO plan_object (branch_id, kind, name, pos_x, pos_y, width, height)
SELECT id, 'KIOSK', '키오스크', 17.5, 16.2, 2, 1.6 FROM branch;
INSERT INTO plan_object (branch_id, kind, name, pos_x, pos_y, width, height)
SELECT id, 'LOCKER', '사물함', 21.5, 16.2, 3.5, 1.6 FROM branch;
