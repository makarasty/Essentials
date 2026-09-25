DELETE FROM player_achievements
WHERE player_id IN (
    SELECT id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY account_id
                   ORDER BY last_login_date DESC NULLS LAST, id DESC
               ) AS duplicate_rank
        FROM players
        WHERE account_id IS NOT NULL AND account_id <> ''
    ) ranked
    WHERE duplicate_rank > 1
);

DELETE FROM player_contributions
WHERE player_id IN (
    SELECT id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY account_id
                   ORDER BY last_login_date DESC NULLS LAST, id DESC
               ) AS duplicate_rank
        FROM players
        WHERE account_id IS NOT NULL AND account_id <> ''
    ) ranked
    WHERE duplicate_rank > 1
);

DELETE FROM players
WHERE id IN (
    SELECT id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY account_id
                   ORDER BY last_login_date DESC NULLS LAST, id DESC
               ) AS duplicate_rank
        FROM players
        WHERE account_id IS NOT NULL AND account_id <> ''
    ) ranked
    WHERE duplicate_rank > 1
);

UPDATE players
SET account_id = NULL
WHERE account_id = '';

CREATE UNIQUE INDEX IF NOT EXISTS players_account_id_nonempty_unique
    ON players (account_id);
