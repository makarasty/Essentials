-- Account IDs are looked up ignoring case, as MySQL's collation always compared them, so the uniqueness
-- has to ignore case too: V8's index on the bare column would let two servers register "Bob" and "bob".
-- PostgreSQL only: MySQL's collation already folds case in V8's index, and H2 has no expression index.
CREATE UNIQUE INDEX IF NOT EXISTS players_account_id_lower_unique ON players (lower(account_id));
