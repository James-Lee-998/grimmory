package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "virtual_books")
public class VirtualBookEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "virtual_book_id", nullable = false, unique = true)
    private Long virtualBookId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "authors", length = 500)
    private String authors;

    @Builder.Default
    @Column(name = "language", length = 2)
    private String language = "en";

    @Column(name = "issued_date")
    private String issuedDate;

    @Builder.Default
    @Column(name = "is_downloaded", nullable = false)
    private boolean isDownloaded = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder.Default
    @OneToMany(mappedBy = "virtualBookEntity", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<VirtualBookMirror> mirrors = new ArrayList<>();

    public VirtualBookMirror getPreferredMirror() {
        return mirrors.stream().max(Comparator.comparingInt(VirtualBookMirror::getQualityScore)).orElse(null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VirtualBookEntity that = (VirtualBookEntity) o;
        return Objects.equals(virtualBookId, that.virtualBookId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(virtualBookId);
    }

}