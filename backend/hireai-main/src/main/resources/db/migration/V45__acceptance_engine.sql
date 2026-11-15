-- The acceptance engine (spec #87 S5, #98): Check Library entries pass, reject or send to review;
-- Findings are reported beside the verdict and never move money (D7); an Agent may self-report
-- anomalies, which send the result to review; a rejected result is reworked before it is refunded
-- (D17). Plus the first real Template, image_generation@1.

-- 1. A report's verdict may now be REVIEW: no check rejected, but one could not decide or the
--    Agent reported an anomaly. Each check in `checks` also carries its "outcome" (PASS / REJECT /
--    REVIEW); rows written before this read back from "passed".
ALTER TABLE validation_reports DROP CONSTRAINT validation_reports_verdict_check;
ALTER TABLE validation_reports ADD CONSTRAINT validation_reports_verdict_check
    CHECK (verdict IN ('PASS', 'FAIL', 'REVIEW'));

-- 2. What was reported with each attempt: the Findings ([{code, detail}]) and the Agent's
--    self-reported anomalies ([{check, description}]).
ALTER TABLE validation_reports ADD COLUMN findings  JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE validation_reports ADD COLUMN anomalies JSONB NOT NULL DEFAULT '[]'::jsonb;

-- 3. image_generation@1. Its checks are Check Library entries (CheckLibrary, ImageGenerationTemplate
--    lists them in this order); DUPLICATE_BRIEF is a Finding. Every pixel check runs only when its
--    parameters are set, except IMAGE_FORMAT, which always holds the file to an image type.
--    width/height use "max": an Offer names the largest it can draw. colour_tolerance uses "min":
--    an Offer names the tightest tolerance it can be held to (unset = 32 per channel).
INSERT INTO contract_templates (template_key, version, definition) VALUES
('image_generation', 1, '{
  "title": "Generated image",
  "description": "An image delivered by HTTPS link. The platform fetches it once, keeps a frozen copy, and checks its format and, when set, its exact size and background colour (the colour of its edge). A result that fails a check goes back to the Agent with the failed checks, up to 2 times, then is refunded.",
  "outputShape": "FILE",
  "checks": ["FORMAT_FILE_HTTPS_URL", "FILE_FETCHED", "IMAGE_FORMAT", "IMAGE_DIMENSIONS",
             "IMAGE_BACKGROUND_COLOUR", "DUPLICATE_BRIEF"],
  "parameters": [
    {"name": "formats", "label": "Image formats",
     "description": "The image types the Client accepts. Unset accepts PNG, JPEG and WebP. The size and colour of a WebP are judged by the Client, not automatically.",
     "comparison": "subset", "bounds": {"allowedValues": ["image/jpeg", "image/png", "image/webp"]}},
    {"name": "width", "label": "Width (px)",
     "description": "The exact width in pixels. An Offer names the widest image it can draw.",
     "comparison": "max", "bounds": {"minimum": 16, "maximum": 4096}},
    {"name": "height", "label": "Height (px)",
     "description": "The exact height in pixels. An Offer names the tallest image it can draw.",
     "comparison": "max", "bounds": {"minimum": 16, "maximum": 4096}},
    {"name": "background_colour", "label": "Background colour",
     "description": "The colour of the image''s edge, written as #rrggbb (for example #ffffff for white).",
     "comparison": "enum", "bounds": {"maxLength": 7}},
    {"name": "colour_tolerance", "label": "Colour tolerance",
     "description": "How far each red, green and blue channel of the background may be from the colour asked (0 to 255; unset is 32). An Offer names the tightest tolerance it can be held to.",
     "comparison": "min", "bounds": {"minimum": 0, "maximum": 255}}
  ],
  "expectationMode": "BUSINESS_VALID",
  "rulingSettlement": {"FULFILLED": 1, "PARTIALLY_FULFILLED": 0.5, "NOT_FULFILLED": 0}
}');
