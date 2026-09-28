package com.nameless0422.MenuPick.domain.menu;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

@RequiredArgsConstructor
class MenuRepositoryImpl implements MenuRepositoryCustom {
    private final EntityManager entityManager;

    @Override
    public List<PickCandidate> findPickCandidates(Specification<Menu> specification) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = builder.createTupleQuery();
        Root<Menu> menu = query.from(Menu.class);
        query.multiselect(menu.get("id").alias("id"), menu.get("weight").alias("weight"));
        query.where(specification.toPredicate(menu, query, builder));
        return entityManager.createQuery(query).getResultList().stream()
                .map(row -> new PickCandidate(row.get("id", Long.class), row.get("weight", Integer.class)))
                .toList();
    }
}
