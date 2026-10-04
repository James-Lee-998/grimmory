package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.booklore.model.enums.BookFileType;
import java.time.LocalDateTime;
import org.booklore.model.enums.Provider;
import org.booklore.model.entity.VirtualBookEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "virtual_book_mirrors")
public class VirtualBookMirror {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "virtual_book_ref_id")
    @JsonIgnore
    private VirtualBookEntity virtualBookEntity;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider")
    private Provider provider;

    /** The provider's own ID for the book, e.g. the Gutenberg ebook number. */
    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(name = "download_url")
    private String downloadUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_type")
    private BookFileType format;

    @Column(name = "quality_score")
    private int qualityScore;
}
