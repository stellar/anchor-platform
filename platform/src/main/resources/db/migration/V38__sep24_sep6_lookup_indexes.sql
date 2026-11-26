-- Indexes for the SEP-24 and SEP-6 transaction lookups (HackerOne #4240810, ANCHOR-1584).
--
-- Without them every lookup by transaction_id, stellar_transaction_id or external_transaction_id,
-- and every wallet list by web_auth_account, scans the whole table: the primary key is `id`, not
-- one of these columns. The names match the @Table(indexes) of JdbcSep24Transaction and
-- JdbcSep6Transaction, which create the same indexes where Flyway is off (data.flyway_enabled).
--
-- CREATE INDEX blocks writes to the table while it builds (readers are not blocked). On a table
-- with millions of rows, or with rolling deploys, create the indexes beforehand, without blocking,
-- under the same names; this migration then skips them:
--
--   CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sep24_transaction_transaction_id
--     ON sep24_transaction (transaction_id);
--
-- A failed CONCURRENTLY build leaves an INVALID index of that name, which IF NOT EXISTS keeps:
-- drop it and build it again before upgrading.

CREATE INDEX IF NOT EXISTS idx_sep24_transaction_transaction_id
  ON sep24_transaction (transaction_id);
CREATE INDEX IF NOT EXISTS idx_sep24_transaction_stellar_transaction_id
  ON sep24_transaction (stellar_transaction_id);
CREATE INDEX IF NOT EXISTS idx_sep24_transaction_external_transaction_id
  ON sep24_transaction (external_transaction_id);
CREATE INDEX IF NOT EXISTS idx_sep24_transaction_web_auth_account_started_at
  ON sep24_transaction (web_auth_account, started_at);

CREATE INDEX IF NOT EXISTS idx_sep6_transaction_transaction_id
  ON sep6_transaction (transaction_id);
CREATE INDEX IF NOT EXISTS idx_sep6_transaction_stellar_transaction_id
  ON sep6_transaction (stellar_transaction_id);
CREATE INDEX IF NOT EXISTS idx_sep6_transaction_external_transaction_id
  ON sep6_transaction (external_transaction_id);
CREATE INDEX IF NOT EXISTS idx_sep6_transaction_web_auth_account_started_at
  ON sep6_transaction (web_auth_account, started_at);
