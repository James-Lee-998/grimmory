ALTER TABLE virtual_books
    ADD COLUMN IF NOT EXISTS summary TEXT;

-- The mirror FK references virtual_books.id (surrogate key), not virtual_books.virtual_book_id,
-- so rename it to match the entity mapping and avoid confusion with the business ID.
ALTER TABLE virtual_book_mirrors
    DROP FOREIGN KEY IF EXISTS fk_virtual_book_mirrors_virtual_book;
ALTER TABLE virtual_book_mirrors
    RENAME COLUMN virtual_book_id TO virtual_book_ref_id;
ALTER TABLE virtual_book_mirrors
    ADD CONSTRAINT fk_virtual_book_mirrors_virtual_book
        FOREIGN KEY (virtual_book_ref_id) REFERENCES virtual_books (id) ON DELETE CASCADE;
