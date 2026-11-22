package com.nameless0422.MenuPick.domain.nutrition;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface FoodReferenceRepository extends JpaRepository<FoodReference, Long> {

    /**
     * 이름에 검색어가 들어간 기준 음식. 정확히 같은 이름 → 그 이름으로 시작 → 짧은 이름 순이다.
     *
     * <p>"김치찌개"를 찾는 사람에게 "돼지고기김치찌개"보다 "김치찌개"가 먼저 와야 하고, 같은 단계에서는
     * 짧을수록 일반적인 음식일 가능성이 높다. 수천 행이라 앞뒤 와일드카드 LIKE로 충분하다.
     *
     * <p>이름이 같으면 출처 순(외식 → 가정식 → 프랜차이즈 → 급식, {@link FoodReference#origin()})이다.
     * 급식 행은 아이 몫·국물 위주라 같은 이름이라도 열량이 훨씬 작아, 먼저 나오면 대부분 잘못 고른다(D-090).
     *
     * @param exact   사용자가 입력한 그대로(비교용)
     * @param pattern LIKE 특수문자를 {@code !}로 이스케이프한 검색어
     */
    @Query(value = """
            select * from food_references
            where name like concat('%', :pattern, '%') escape '!'
            order by (name = :exact) desc,
                     (name like concat(:pattern, '%') escape '!') desc,
                     char_length(name), name,
                     case left(code, 2) when 'D3' then 0 when 'D4' then 1 when 'D1' then 2 when 'D2' then 3
                                        when 'D7' then 4 when 'D6' then 5 when 'D5' then 6 else 7 end,
                     id
            limit :size
            """, nativeQuery = true)
    List<FoodReference> search(@Param("exact") String exact, @Param("pattern") String pattern,
                               @Param("size") int size);

    List<FoodReference> findByCodeIn(Collection<String> codes);
}
