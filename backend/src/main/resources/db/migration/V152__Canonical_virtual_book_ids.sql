-- virtual_books.virtual_book_id is now a canonical ID derived from the normalised title, author and language
-- (see CanonicalBookId), so the same book from different providers is one virtual book with mirrors from each.
-- The provider's own ID (e.g. the Gutenberg ebook number) lives on the mirror as external_id.
--
-- Rows created before this change used the Gutenberg ebook number as virtual_book_id and cannot be converted in
-- SQL, so they are removed; the Gutenberg seeder recreates them with canonical IDs on the next start.
DELETE FROM virtual_book_mirrors;
DELETE FROM virtual_books;

ALTER TABLE virtual_book_mirrors
    ADD COLUMN IF NOT EXISTS external_id VARCHAR(255) NOT NULL AFTER provider;
ALTER TABLE virtual_book_mirrors
    ADD CONSTRAINT uk_virtual_book_mirrors_provider_external_format UNIQUE (provider, external_id, file_type);
