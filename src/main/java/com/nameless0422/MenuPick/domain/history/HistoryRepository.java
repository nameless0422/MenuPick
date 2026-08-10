package com.nameless0422.MenuPick.domain.history;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface HistoryRepository extends JpaRepository<History, Long> {

    @Modifying
    @Query("delete from HistoryFilterCondition fc where fc.history.id in " +
            "(select h.id from History h where h.user.id = :userId)")
    void deleteFilterConditionsByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("delete from History h where h.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);

    List<History> findByUserIdAndRecommendedAtAfterAndIdLessThanOrderByIdDesc(
            Long userId, LocalDateTime after, Long cursor, Pageable pageable);

    List<History> findByUserIdAndRecommendedAtAfterOrderByIdDesc(
            Long userId, LocalDateTime after, Pageable pageable);

    Optional<History> findByIdAndUserId(Long id, Long userId);
}
