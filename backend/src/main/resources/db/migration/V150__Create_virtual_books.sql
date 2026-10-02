CREATE TABLE IF NOT EXISTS virtual_books
(
    id              BIGINT AUTO_INCREMENT NOT NULL,
    virtual_book_id BIGINT                NOT NULL,
    title           VARCHAR(255)          NOT NULL,
    authors         VARCHAR(500),
    language        VARCHAR(2),
    issued_date     VARCHAR(50),
    is_downloaded   BOOLEAN               NOT NULL DEFAULT FALSE,
    created_at      DATETIME,
    updated_at      DATETIME              NOT NULL,
    CONSTRAINT pk_virtual_books PRIMARY KEY (id),
    CONSTRAINT uk_virtual_books_virtual_book_id UNIQUE (virtual_book_id)
);

CREATE TABLE IF NOT EXISTS virtual_book_mirrors
(
    id              BIGINT AUTO_INCREMENT NOT NULL,
    virtual_book_id BIGINT                NOT NULL,
    provider        VARCHAR(50),
    download_url    VARCHAR(2048),
    file_type       VARCHAR(20),
    quality_score   INT                   NOT NULL DEFAULT 0,
    CONSTRAINT pk_virtual_book_mirrors PRIMARY KEY (id),
    CONSTRAINT fk_virtual_book_mirrors_virtual_book
        FOREIGN KEY (virtual_book_id) REFERENCES virtual_books (id) ON DELETE CASCADE
);
