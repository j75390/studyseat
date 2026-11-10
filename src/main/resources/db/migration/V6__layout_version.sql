-- 도면 배치 버전. 1 = 초기 격자 배치(V3·V4), 2 = 도면 엔진(FloorPlanLayout)이 배치한 건축 도면형.
-- 앱이 시작할 때 버전이 2 미만인 지점은 좌석 수를 유지한 채 새 도면으로 다시 배치된다(FloorPlanService).
ALTER TABLE branch ADD COLUMN layout_ver INT NOT NULL DEFAULT 1;
