package com.nameless0422.MenuPick.domain.trend;

import com.nameless0422.MenuPick.domain.menu.DefaultMenus;
import java.util.List;
import java.util.Set;

/** 공개 집계에 허용되는 닫힌 어휘. 사용자 자유 문자열은 이 경계를 통과하지 않는다. */
public final class TrendVocabulary {
    public static final List<String> CATEGORIES = List.of(
            "한식", "중식", "일식", "양식", "분식", "아시안", "패스트푸드", "카페·디저트");
    public static final List<String> MENU_NAMES = DefaultMenus.PRESETS.stream()
            .map(DefaultMenus.Preset::name).distinct().toList();
    private static final Set<String> CATEGORY_SET = Set.copyOf(CATEGORIES);
    private static final Set<String> MENU_SET = Set.copyOf(MENU_NAMES);
    public static boolean allows(TrendDimension dimension, String label) {
        return label != null && (dimension == TrendDimension.CATEGORY
                ? CATEGORY_SET.contains(label) : MENU_SET.contains(label));
    }
    private TrendVocabulary() {}
}
