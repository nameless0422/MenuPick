package com.nameless0422.MenuPick.domain.trend;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.Optional;

public interface PickTrendSnapshotRepository extends JpaRepository<PickTrendSnapshot, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PickTrendSnapshot s where s.id = 1")
    Optional<PickTrendSnapshot> lockSingleton();
}
