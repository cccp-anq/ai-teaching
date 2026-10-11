-- DEC-015 approved the first teaching week of 2026-FALL as Sunday 2026-08-30.
-- Preserve a date already supplied by an authoritative calendar source.
UPDATE semester
SET first_week_date = '2026-08-30', updated_at = CURRENT_TIMESTAMP
WHERE semester_code = '2026-FALL' AND first_week_date IS NULL;
