package com.studyseat.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 지점 도면 엔진. 좌석 수(일반석·부스·라운지·스터디룸)를 받아 실제 매장 도면처럼 배치한다:
 * 바깥 벽, 문이 달린 방(스터디룸), 벽을 따라 놓인 스터디존 책상, 칸막이가 있는 책상 섬(카페존),
 * 사물함 줄, 서비스바, 키오스크, 입구. 좌표는 32 x 20 칸 격자다.
 *
 * 스타일 0·1: "홀형" — 왼쪽(또는 오른쪽 거울) 벽을 따라 방, 위쪽에 스터디존, 가운데 책상 섬 2개
 * 스타일 2·3: "도서관형" — 위쪽에 방, 가운데 긴 책상 줄 4줄(두 줄씩 칸막이)
 * 홀수 스타일은 좌우를 뒤집은 거울 배치다. 좌석 순서(번호)는 배치 순서(위→아래, 왼쪽→오른쪽)를 따른다.
 */
public final class FloorPlanLayout {

    public static final int W = 32;
    public static final int H = 20;

    public static final double DESK_W = 2.0;
    public static final double DESK_H = 1.5;
    public static final double BOOTH_W = 2.2;
    public static final double BOOTH_H = 1.7;
    public static final double LOUNGE_W = 2.4;
    public static final double LOUNGE_H = 1.8;
    public static final double ROOM_W = 5.0;
    public static final double ROOM_H = 3.4;

    public static final int MAX_DESK_HALL = 24;
    public static final int MAX_DESK_LIBRARY = 32;
    public static final int MAX_BOOTH = 8;
    public static final int MAX_LOUNGE = 6;
    public static final int MAX_ROOM = 4;

    /** 사각형(x, y, w, h) */
    public record Rect(double x, double y, double w, double h) {
        Rect mirror() {
            return new Rect(round(W - x - w), y, w, h);
        }

        public boolean overlaps(Rect o) {
            return x < o.x + o.w - 1e-9 && o.x < x + w - 1e-9 && y < o.y + o.h - 1e-9 && o.y < y + h - 1e-9;
        }
    }

    /** 도면 장식 요소: kind = WALL / ENTRANCE / KIOSK / LOCKER / COUNTER / RESTROOM / STORAGE / TABLE / LABEL */
    public record Obj(String kind, String name, Rect rect) {
    }

    public record Layout(int style, List<Rect> desks, List<Rect> booths, List<Rect> lounges, List<Rect> rooms,
                         Rect deskZone, Rect boothZone, Rect loungeZone, Rect roomZone, List<Obj> objects) {
    }

    private FloorPlanLayout() {
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static Rect r(double x, double y, double w, double h) {
        return new Rect(round(x), round(y), round(w), round(h));
    }

    public static int maxDesks(int style) {
        return style < 2 ? MAX_DESK_HALL : MAX_DESK_LIBRARY;
    }

    public static Layout arrange(int style, int nDesk, int nBooth, int nLounge, int nRoom) {
        if (style < 0 || style > 3) {
            throw new IllegalArgumentException("알 수 없는 도면 스타일: " + style);
        }
        if (nDesk > maxDesks(style) || nBooth > MAX_BOOTH || nLounge > MAX_LOUNGE || nRoom > MAX_ROOM
                || nDesk < 0 || nBooth < 0 || nLounge < 0 || nRoom < 0) {
            throw new IllegalArgumentException(String.format("좌석 수가 도면 용량을 넘습니다: 일반 %d 부스 %d 라운지 %d 룸 %d", nDesk, nBooth, nLounge, nRoom));
        }
        boolean mirror = style % 2 == 1;
        Layout base = style < 2 ? hall(nDesk, nBooth, nLounge, nRoom) : library(nDesk, nBooth, nLounge, nRoom);
        return mirror ? mirrorOf(base, style) : withStyle(base, style);
    }

    private static Layout withStyle(Layout l, int style) {
        return new Layout(style, l.desks(), l.booths(), l.lounges(), l.rooms(), l.deskZone(), l.boothZone(),
                l.loungeZone(), l.roomZone(), l.objects());
    }

    private static Layout mirrorOf(Layout l, int style) {
        List<Obj> objs = new ArrayList<>();
        for (Obj o : l.objects()) {
            objs.add(new Obj(o.kind(), o.name(), o.rect().mirror()));
        }
        return new Layout(style, mirrorAll(l.desks()), mirrorAll(l.booths()), mirrorAll(l.lounges()), mirrorAll(l.rooms()),
                l.deskZone().mirror(), l.boothZone().mirror(), l.loungeZone().mirror(), l.roomZone().mirror(), objs);
    }

    private static List<Rect> mirrorAll(List<Rect> in) {
        List<Rect> out = new ArrayList<>();
        for (Rect x : in) {
            out.add(x.mirror());
        }
        return out;
    }

    // ---------------------------------------------------------------- 공통: 바깥 벽·시설

    private static void perimeter(List<Obj> o) {
        o.add(new Obj("WALL", "", r(0, 0, W, 0.4)));
        o.add(new Obj("WALL", "", r(0, 0, 0.4, H)));
        o.add(new Obj("WALL", "", r(W - 0.4, 0, 0.4, H)));
        // 아래 벽: 입구(16.5~20.5)를 비워 둔다
        o.add(new Obj("WALL", "", r(0, H - 0.4, 16.5, 0.4)));
        o.add(new Obj("WALL", "", r(20.5, H - 0.4, W - 20.5, 0.4)));
    }

    private static void frontDesk(List<Obj> o) {
        o.add(new Obj("ENTRANCE", "입구", r(16.6, 19.0, 3.8, 0.9)));
        o.add(new Obj("KIOSK", "키오스크", r(20.8, 17.0, 2.0, 1.6)));
        o.add(new Obj("COUNTER", "서비스바", r(23.4, 16.0, 7.6, 2.6)));
    }

    // ---------------------------------------------------------------- 홀형

    private static Layout hall(int nDesk, int nBooth, int nLounge, int nRoom) {
        List<Obj> objs = new ArrayList<>();
        perimeter(objs);
        frontDesk(objs);

        // 방: 왼쪽 열, 오른쪽 벽(x=6.2)에 방마다 문(틈)
        List<Rect> rooms = new ArrayList<>();
        double roomTop = 1.0;
        double pitch = ROOM_H + 0.4;
        for (int i = 0; i < nRoom; i++) {
            rooms.add(r(0.8, roomTop + pitch * i, ROOM_W, ROOM_H));
        }
        if (nRoom > 0) {
            double bottom = roomTop + pitch * (nRoom - 1) + ROOM_H + 0.4;
            double y = 0.4;
            for (int i = 0; i < nRoom; i++) {
                double doorFrom = roomTop + pitch * i + ROOM_H / 2 - 0.7;
                double doorTo = doorFrom + 1.4;
                if (doorFrom > y) {
                    objs.add(new Obj("WALL", "", r(6.2, y, 0.3, doorFrom - y)));
                }
                y = doorTo;
            }
            if (bottom > y) {
                objs.add(new Obj("WALL", "", r(6.2, y, 0.3, bottom - y)));
            }
            objs.add(new Obj("WALL", "", r(0.4, bottom, 5.9, 0.3)));
            objs.add(new Obj("STORAGE", "창고", r(0.8, bottom + 0.8, 5.0, 2.2)));
        }
        Rect roomZone = nRoom > 0 ? r(0.6, 0.8, 5.8, pitch * nRoom + 0.2) : r(0.6, 0.8, 5.8, 3.0);

        // 스터디존: 위쪽 벽을 따라 한 줄
        List<Rect> desks = new ArrayList<>();
        int top = Math.min(nDesk, 8);
        for (int i = 0; i < top; i++) {
            desks.add(r(7.2 + 2.4 * i, 0.9, DESK_W, DESK_H));
        }
        if (nDesk > 0) {
            objs.add(new Obj("LABEL", "STUDY ZONE", r(7.2, 2.7, 8.0, 0.9)));
        }
        // 카페존: 책상 섬 2개(각 2줄 x 4칸, 가운데 칸막이)
        int rest = nDesk - top;
        double[][] islands = {{7.6, 6.4}, {7.6, 11.0}};
        int placed = 0;
        for (double[] isl : islands) {
            if (placed >= rest) {
                break;
            }
            int inIsland = Math.min(8, rest - placed);
            for (int k = 0; k < inIsland; k++) {
                int row = k / 4;
                int col = k % 4;
                desks.add(r(isl[0] + 2.4 * col, isl[1] + 1.9 * row, DESK_W, DESK_H));
            }
            objs.add(new Obj("WALL", "", r(isl[0] - 0.2, isl[1] + 1.65, 9.8, 0.15)));
            placed += inIsland;
        }
        if (rest > 0) {
            objs.add(new Obj("LABEL", "CAFE ZONE", r(7.6, 5.3, 8.0, 0.9)));
            objs.add(new Obj("TABLE", "", r(20.6, 7.2, 2.2, 2.2)));
            objs.add(new Obj("TABLE", "", r(23.0, 10.4, 2.2, 2.2)));
        }
        Rect deskZone = r(6.8, 0.6, 19.6, 14.2);

        // 부스: 오른쪽 벽 두 줄
        List<Rect> booths = new ArrayList<>();
        for (int i = 0; i < nBooth; i++) {
            double x = i < 4 ? 26.8 : 29.2;
            booths.add(r(x, 6.6 + 2.1 * (i % 4), BOOTH_W, BOOTH_H));
        }
        Rect boothZone = r(26.6, 6.2, 5.0, 9.0);

        // 라운지: 왼쪽 아래 소파
        List<Rect> lounges = new ArrayList<>();
        for (int i = 0; i < nLounge; i++) {
            lounges.add(r(7.6 + 2.8 * (i % 3), i < 3 ? 15.2 : 17.0, LOUNGE_W, LOUNGE_H));
        }
        Rect loungeZone = r(7.2, 14.8, 8.8, 4.4);

        // 오른쪽 위: 사물함 줄, 화장실
        objs.add(new Obj("LOCKER", "사물함", r(26.6, 0.8, 4.6, 1.7)));
        objs.add(new Obj("RESTROOM", "화장실", r(26.6, 3.0, 4.6, 2.6)));
        return new Layout(0, desks, booths, lounges, rooms, deskZone, boothZone, loungeZone, roomZone, objs);
    }

    // ---------------------------------------------------------------- 도서관형

    private static Layout library(int nDesk, int nBooth, int nLounge, int nRoom) {
        List<Obj> objs = new ArrayList<>();
        perimeter(objs);
        frontDesk(objs);

        // 방: 위쪽 가로 줄, 아래쪽 벽(y=4.6)에 문
        List<Rect> rooms = new ArrayList<>();
        for (int i = 0; i < nRoom; i++) {
            rooms.add(r(0.8 + 5.4 * i, 0.9, ROOM_W, ROOM_H));
        }
        if (nRoom > 0) {
            double right = 0.8 + 5.4 * (nRoom - 1) + ROOM_W + 0.4;
            double x = 0.4;
            for (int i = 0; i < nRoom; i++) {
                double doorFrom = 0.8 + 5.4 * i + ROOM_W / 2 - 0.7;
                if (doorFrom > x) {
                    objs.add(new Obj("WALL", "", r(x, 4.5, doorFrom - x, 0.3)));
                }
                x = doorFrom + 1.4;
            }
            if (right > x) {
                objs.add(new Obj("WALL", "", r(x, 4.5, right - x, 0.3)));
            }
            objs.add(new Obj("WALL", "", r(right, 0.4, 0.3, 4.4)));
        }
        Rect roomZone = r(0.6, 0.7, 5.4 * Math.max(nRoom, 1) + 0.4, 3.8);

        // 가운데: 긴 책상 줄 4줄(두 줄씩 칸막이), 8칸씩
        List<Rect> desks = new ArrayList<>();
        double[] rows = {6.2, 8.1, 10.9, 12.8};
        for (int k = 0; k < nDesk; k++) {
            int row = k / 8;
            int col = k % 8;
            desks.add(r(2.0 + 2.4 * col, rows[row], DESK_W, DESK_H));
        }
        if (nDesk > 0) {
            int usedRows = (nDesk + 7) / 8;
            if (usedRows >= 2) {
                objs.add(new Obj("WALL", "", r(1.8, 7.85, 19.2, 0.15)));
            }
            if (usedRows >= 4) {
                objs.add(new Obj("WALL", "", r(1.8, 12.55, 19.2, 0.15)));
            }
            objs.add(new Obj("LABEL", "STUDY ZONE", r(2.0, 5.2, 8.0, 0.9)));
        }
        Rect deskZone = r(1.4, 5.0, 20.8, 10.0);

        // 부스: 오른쪽 벽 두 줄
        List<Rect> booths = new ArrayList<>();
        for (int i = 0; i < nBooth; i++) {
            double x = i < 4 ? 26.8 : 29.2;
            booths.add(r(x, 6.6 + 2.1 * (i % 4), BOOTH_W, BOOTH_H));
        }
        Rect boothZone = r(26.6, 6.2, 5.0, 9.0);

        // 라운지: 왼쪽 아래
        List<Rect> lounges = new ArrayList<>();
        for (int i = 0; i < nLounge; i++) {
            lounges.add(r(2.0 + 2.8 * (i % 3), i < 3 ? 15.6 : 17.4, LOUNGE_W, LOUNGE_H));
        }
        Rect loungeZone = r(1.6, 15.2, 8.8, 4.2);

        objs.add(new Obj("LOCKER", "사물함", r(26.6, 0.8, 4.6, 1.7)));
        objs.add(new Obj("RESTROOM", "화장실", r(26.6, 3.0, 4.6, 2.6)));
        objs.add(new Obj("TABLE", "", r(22.8, 7.4, 2.2, 2.2)));
        objs.add(new Obj("TABLE", "", r(22.8, 11.4, 2.2, 2.2)));
        return new Layout(2, desks, booths, lounges, rooms, deskZone, boothZone, loungeZone, roomZone, objs);
    }
}
