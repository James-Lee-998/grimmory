package org.booklore.repository;

import org.booklore.model.entity.VirtualBookEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface VirtualBookRepository extends JpaRepository<VirtualBookEntity, Long> {

    Optional<VirtualBookEntity> findByVirtualBookId(@Param("virtualBookId") long virtualBookId);

    boolean existsByVirtualBookId(Long virtualBookId);

    Page<VirtualBookEntity>findByTitleContainingIgnoreCaseOrAuthorsContainingIgnoreCase(
        String titleKeyword,
        String authorKeyword,
        Pageable pageable
    );

}