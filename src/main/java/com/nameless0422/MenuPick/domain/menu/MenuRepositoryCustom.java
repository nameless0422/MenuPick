package com.nameless0422.MenuPick.domain.menu;

import org.springframework.data.jpa.domain.Specification;

import java.util.List;

public interface MenuRepositoryCustom {
    record PickCandidate(Long id, int weight) {}

    List<PickCandidate> findPickCandidates(Specification<Menu> specification);
}
