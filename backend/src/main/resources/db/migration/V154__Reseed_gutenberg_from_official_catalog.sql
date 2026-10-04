-- The Gutenberg seed switched from a third-party extract with corrupted non-ASCII text (e.g. "K??Miksz?") to
-- Gutenberg's official pg_catalog.csv. Remove the old Gutenberg rows so the seeder re-imports them on next start;
-- books that still have mirrors from other providers are kept.
DELETE FROM virtual_book_mirrors WHERE provider = 'GUTENBERG';
DELETE FROM virtual_books
WHERE NOT EXISTS (SELECT 1 FROM virtual_book_mirrors m WHERE m.virtual_book_ref_id = virtual_books.id);
