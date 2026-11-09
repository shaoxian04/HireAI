-- V35: the platform-owned category list (#80, spec #59, ADR 0006). Builders and Clients share one
-- coarse vocabulary instead of free-text tags, so a typo can no longer mean "no eligible agent".
-- The list is reference data: seeded and changed ONLY by Flyway migrations (no admin UI).

CREATE TABLE categories (
    slug        TEXT PRIMARY KEY CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    label       TEXT NOT NULL,
    sort_order  INT NOT NULL,
    gmt_create  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- document-extraction is the launch vertical (ADR 0004); "other" catches niche agents until the
-- evidence (suggestions, clusters inside other) justifies a new bucket.
INSERT INTO categories (slug, label, sort_order) VALUES
    ('document-extraction', 'Document extraction', 10),
    ('summarisation', 'Summarisation', 20),
    ('translation', 'Translation', 30),
    ('writing', 'Writing and editing', 40),
    ('research', 'Research', 50),
    ('data-analysis', 'Data analysis', 60),
    ('data-transformation', 'Data cleaning and conversion', 70),
    ('classification', 'Classification and tagging', 80),
    ('code', 'Code', 90),
    ('image', 'Image', 100),
    ('audio', 'Audio and transcription', 110),
    ('customer-support', 'Customer support', 120),
    ('other', 'Other', 1000);

-- A Builder's optional "suggest a category" text, stored for admins to review. One row per
-- registration or published version that carried a suggestion. Soft references (no FK), like the
-- other cross-context tables, so tests that clear agents/versions are not blocked.
CREATE TABLE category_suggestions (
    id                  UUID PRIMARY KEY,
    agent_id            UUID NOT NULL,
    agent_version_id    UUID NOT NULL,
    owner_id            UUID NOT NULL,
    suggested_category  TEXT NOT NULL CHECK (char_length(suggested_category) BETWEEN 1 AND 100),
    gmt_create          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_category_suggestions_created ON category_suggestions (gmt_create);

-- Map existing free-text tags onto slugs. The lookup key is the tag lowercased, trimmed, with runs
-- of spaces/underscores turned into one hyphen. Every slug is its own alias; anything unknown
-- becomes 'other'.
CREATE TEMP TABLE category_aliases (alias TEXT PRIMARY KEY, slug TEXT NOT NULL) ON COMMIT DROP;

INSERT INTO category_aliases (alias, slug)
SELECT slug, slug FROM categories;

INSERT INTO category_aliases (alias, slug) VALUES
    ('extraction', 'document-extraction'), ('data-extraction', 'document-extraction'),
    ('document-parsing', 'document-extraction'), ('parsing', 'document-extraction'),
    ('ocr', 'document-extraction'), ('invoice', 'document-extraction'),
    ('invoices', 'document-extraction'), ('invoice-extraction', 'document-extraction'),
    ('receipt', 'document-extraction'), ('receipts', 'document-extraction'),
    ('pdf', 'document-extraction'), ('pdf-extraction', 'document-extraction'),
    ('summarization', 'summarisation'), ('summary', 'summarisation'),
    ('summaries', 'summarisation'), ('summarise', 'summarisation'),
    ('summarize', 'summarisation'), ('summariser', 'summarisation'),
    ('summarizer', 'summarisation'), ('summarizing', 'summarisation'),
    ('summarising', 'summarisation'),
    ('translate', 'translation'), ('translator', 'translation'),
    ('localisation', 'translation'), ('localization', 'translation'),
    ('copywriting', 'writing'), ('content', 'writing'), ('content-writing', 'writing'),
    ('editing', 'writing'), ('proofreading', 'writing'), ('blog', 'writing'),
    ('web-research', 'research'), ('search', 'research'),
    ('analysis', 'data-analysis'), ('analytics', 'data-analysis'),
    ('data-cleaning', 'data-transformation'), ('etl', 'data-transformation'),
    ('conversion', 'data-transformation'), ('formatting', 'data-transformation'),
    ('transformation', 'data-transformation'),
    ('tagging', 'classification'), ('categorisation', 'classification'),
    ('categorization', 'classification'), ('sentiment', 'classification'),
    ('sentiment-analysis', 'classification'), ('moderation', 'classification'),
    ('coding', 'code'), ('programming', 'code'), ('code-review', 'code'),
    ('software', 'code'),
    ('images', 'image'), ('image-generation', 'image'), ('vision', 'image'),
    ('transcription', 'audio'), ('speech', 'audio'), ('speech-to-text', 'audio'),
    ('support', 'customer-support'), ('customer-service', 'customer-support');

CREATE FUNCTION pg_temp.category_slug(tag TEXT) RETURNS TEXT
    LANGUAGE sql STABLE AS $$
    SELECT COALESCE(
        (SELECT a.slug FROM category_aliases a
          WHERE a.alias = regexp_replace(lower(trim(tag)), '[\s_]+', '-', 'g')),
        'other')
$$;

-- Keep the first-seen order of the mapped slugs and drop duplicates. An empty array (never valid
-- for a version) becomes {other}.
UPDATE agent_versions v SET capability_categories = COALESCE(
    (SELECT array_agg(m.slug ORDER BY m.first_pos)
       FROM (SELECT pg_temp.category_slug(t.tag) AS slug, MIN(t.pos) AS first_pos
               FROM unnest(v.capability_categories) WITH ORDINALITY AS t(tag, pos)
              GROUP BY 1) m),
    ARRAY['other']);

UPDATE tasks SET category = pg_temp.category_slug(category)
WHERE category IS NOT NULL;
