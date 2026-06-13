package com.nameless0422.MenuPick.domain.tag;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, Long> {

    Optional<Tag> findByUserIdAndName(Long userId, String name);

    List<Tag> findByUserIdAndNameStartingWith(Long userId, String prefix);
}
