DELETE FROM player_achievements
WHERE player_id IN (
    SELECT id
    FROM (
        SELECT id, ROW_NUMBER() OVER (
            PARTITION BY uuid
            ORDER BY
                CASE WHEN last_login_date IS NULL THEN 1 ELSE 0 END ASC,
                last_login_date DESC,
                level DESC,
                id ASC
        ) as rn
        FROM players
    ) tmp
    WHERE rn > 1
);

DELETE FROM players
WHERE id IN (
    SELECT id
    FROM (
        SELECT id, ROW_NUMBER() OVER (
            PARTITION BY uuid
            ORDER BY
                CASE WHEN last_login_date IS NULL THEN 1 ELSE 0 END ASC,
                last_login_date DESC,
                level DESC,
                id ASC
        ) as rn
        FROM players
    ) tmp
    WHERE rn > 1
);

/* Fix NULL last_login_date left by v4 migration */
UPDATE players SET last_login_date = CURRENT_TIMESTAMP WHERE last_login_date IS NULL;

/* map_ratings is created by SchemaUtils, which runs after this script, so it is absent altogether on a
   version 3 database and present carrying is_upvote on a server that ran the build declaring it that
   way. One failed statement aborts the transaction this whole script shares, which used to abandon the
   upgrade with the version stamp left behind, so every statement below has to survive all three: the
   table absent, the column absent, and having run once already.

   is_upvote is added back so the conversion can name it. Where the column was already gone it is null
   and each row keeps the rating it had. Where it is real it is boolean, the only shape this codebase
   ever declared it in - the generic v5.sql also compares it to 1, which nothing here can produce.

   Nothing in this file, comments included, may contain a semicolon: Database.kt splits the script on
   one, so a semicolon here cuts a comment in half and feeds both halves to the engine as statements. */
ALTER TABLE IF EXISTS map_ratings DROP CONSTRAINT IF EXISTS map_ratings_map_hash_unique;
ALTER TABLE IF EXISTS map_ratings ADD COLUMN IF NOT EXISTS is_upvote BOOLEAN;
ALTER TABLE IF EXISTS map_ratings ADD COLUMN IF NOT EXISTS difficulty INT DEFAULT 3;
ALTER TABLE IF EXISTS map_ratings ADD COLUMN IF NOT EXISTS rating INT DEFAULT 3;
ALTER TABLE IF EXISTS map_ratings ALTER COLUMN rating TYPE INT USING (CASE WHEN is_upvote IS NULL THEN rating WHEN is_upvote THEN 5 ELSE 1 END);
ALTER TABLE IF EXISTS map_ratings DROP COLUMN IF EXISTS is_upvote;

/* 판당 기여도 점수 테이블 */
CREATE TABLE IF NOT EXISTS player_contributions (
    id BIGSERIAL PRIMARY KEY,
    player_id BIGINT NOT NULL,
    game_mode VARCHAR(20) NOT NULL,
    map_name VARCHAR(64),
    score DOUBLE PRECISION NOT NULL,
    recorded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_player_contributions_player_id__id FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
