package com.nameless0422.MenuPick.domain.tag;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, Long> {

    Optional<Tag> findByUserIdAndName(Long userId, String name);

    List<Tag> findByUserIdAndNameStartingWith(Long userId, String prefix);

    List<Tag> findAllByIdInAndUserId(Iterable<Long> ids, Long userId);

    @Modifying
    @Query("delete from Tag t where t.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
