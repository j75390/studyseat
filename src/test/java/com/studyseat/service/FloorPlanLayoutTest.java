package com.studyseat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.studyseat.service.FloorPlanLayout.Layout;
import com.studyseat.service.FloorPlanLayout.Obj;
import com.studyseat.service.FloorPlanLayout.Rect;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 도면 엔진이 어떤 좌석 수에서도 "건축 도면다운" 배치를 내놓는지 확인한다: 벽 안, 겹침 없음, 구역 안. */
class FloorPlanLayoutTest {

    private static final List<String> SOLID = List.of("WALL", "LOCKER", "RESTROOM", "COUNTER", "KIOSK", "STORAGE", "TABLE", "ENTRANCE");

    private static boolean inside(Rect inner, Rect outer) {
        return inner.x() >= outer.x() - 1e-6 && inner.y() >= outer.y() - 1e-6
                && inner.x() + inner.w() <= outer.x() + outer.w() + 1e-6 && inner.y() + inner.h() <= outer.y() + outer.h() + 1e-6;
    }

    private void check(Layout l, int desk, int booth, int lounge, int room, String label) {
        assertThat(l.desks()).as(label + " 일반석 수").hasSize(desk);
        assertThat(l.booths()).as(label + " 부스 수").hasSize(booth);
        assertThat(l.lounges()).as(label + " 라운지 수").hasSize(lounge);
        assertThat(l.rooms()).as(label + " 룸 수").hasSize(room);

        List<Rect> all = new ArrayList<>();
        all.addAll(l.desks());
        all.addAll(l.booths());
        all.addAll(l.lounges());
        all.addAll(l.rooms());
        Rect inner = new Rect(0.4, 0.4, FloorPlanLayout.W - 0.8, FloorPlanLayout.H - 0.8);
        for (Rect s : all) {
            assertThat(inside(s, inner)).as(label + " 벽 안쪽: " + s).isTrue();
        }
        // 좌석끼리 겹치지 않는다
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                assertThat(all.get(i).overlaps(all.get(j))).as(label + " 좌석 겹침 " + all.get(i) + " / " + all.get(j)).isFalse();
            }
        }
        // 좌석이 벽·시설과 겹치지 않는다
        for (Rect s : all) {
            for (Obj o : l.objects()) {
                if (SOLID.contains(o.kind())) {
                    assertThat(s.overlaps(o.rect())).as(label + " " + o.kind() + " 와 겹침 " + s).isFalse();
                }
            }
        }
        // 좌석은 자기 구역 영역 안에 있다
        l.desks().forEach(s -> assertThat(inside(s, l.deskZone())).as(label + " 일반석이 구역 밖").isTrue());
        l.booths().forEach(s -> assertThat(inside(s, l.boothZone())).as(label + " 부스가 구역 밖").isTrue());
        l.lounges().forEach(s -> assertThat(inside(s, l.loungeZone())).as(label + " 라운지가 구역 밖").isTrue());
        l.rooms().forEach(s -> assertThat(inside(s, l.roomZone())).as(label + " 룸이 구역 밖").isTrue());
        // 시설은 도면 안에 있고 입구·키오스크·사물함·서비스바가 있다
        for (Obj o : l.objects()) {
            assertThat(inside(o.rect(), new Rect(0, 0, FloorPlanLayout.W, FloorPlanLayout.H))).as(label + " 시설 범위 " + o.kind()).isTrue();
        }
        assertThat(l.objects().stream().map(Obj::kind)).contains("ENTRANCE", "KIOSK", "LOCKER", "COUNTER", "RESTROOM", "WALL");
    }

    @Test
    void 모든_스타일과_좌석_수_조합에서_도면이_벽_안에_겹침_없이_배치된다() {
        int cases = 0;
        for (int style = 0; style < 4; style++) {
            int max = FloorPlanLayout.maxDesks(style);
            for (int desk : new int[] {0, 1, 8, 9, 16, 17, 24, max}) {
                for (int booth : new int[] {0, 4, 5, 8}) {
                    for (int lounge : new int[] {0, 3, 6}) {
                        for (int room : new int[] {0, 1, 4}) {
                            Layout l = FloorPlanLayout.arrange(style, desk, booth, lounge, room);
                            check(l, desk, booth, lounge, room, "style" + style + " d" + desk + " b" + booth + " l" + lounge + " r" + room);
                            cases++;
                        }
                    }
                }
            }
        }
        assertThat(cases).isGreaterThan(500);
    }

    @Test
    void 홀형은_왼쪽에_방_위쪽에_스터디존_가운데_책상_섬이고_도서관형은_위쪽에_방_가운데_긴_책상_줄이다() {
        Layout hall = FloorPlanLayout.arrange(0, 22, 8, 0, 4);
        assertThat(hall.rooms()).allSatisfy(r -> assertThat(r.x()).isLessThan(6)); // 왼쪽 열
        assertThat(hall.desks().get(0).y()).isLessThan(2); // 스터디존은 위쪽 벽 줄
        assertThat(hall.objects().stream().filter(o -> o.kind().equals("LABEL")).map(Obj::name)).contains("STUDY ZONE", "CAFE ZONE");

        Layout library = FloorPlanLayout.arrange(2, 24, 8, 0, 4);
        assertThat(library.rooms()).allSatisfy(r -> assertThat(r.y()).isLessThan(2)); // 위쪽 가로 줄
        assertThat(library.desks().stream().map(Rect::y).distinct().count()).isEqualTo(3); // 8칸씩 3줄
    }

    @Test
    void 거울_스타일은_좌우만_뒤집힌_같은_배치다() {
        Layout a = FloorPlanLayout.arrange(0, 22, 6, 0, 3);
        Layout b = FloorPlanLayout.arrange(1, 22, 6, 0, 3);
        for (int i = 0; i < a.desks().size(); i++) {
            assertThat(a.desks().get(i).y()).isEqualTo(b.desks().get(i).y());
            assertThat(a.desks().get(i).x() + a.desks().get(i).w() + b.desks().get(i).x()).isEqualTo((double) FloorPlanLayout.W);
        }
    }

    @Test
    void 좌석_수가_도면_용량을_넘으면_거부한다() {
        assertThatThrownBy(() -> FloorPlanLayout.arrange(0, 25, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FloorPlanLayout.arrange(2, 33, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FloorPlanLayout.arrange(0, 10, 9, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FloorPlanLayout.arrange(0, 10, 0, 7, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FloorPlanLayout.arrange(0, 10, 0, 0, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FloorPlanLayout.arrange(4, 1, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
