-- (provider, external_id, file_type) is not unique: a provider can offer several variants of one format,
-- e.g. Gutenberg's EPUB with and without images. Keep a plain index for the seeder's "already imported" lookup.
ALTER TABLE virtual_book_mirrors
    DROP INDEX IF EXISTS uk_virtual_book_mirrors_provider_external_format;
CREATE INDEX IF NOT EXISTS idx_virtual_book_mirrors_provider_external
    ON virtual_book_mirrors (provider, external_id);
