DELETE FROM player_achievements
WHERE player_id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY account_id
                   ORDER BY
                       CASE WHEN last_login_date IS NULL THEN 1 ELSE 0 END ASC,
                       last_login_date DESC,
                       id DESC
               ) AS duplicate_rank
        FROM players
        WHERE account_id IS NOT NULL AND account_id <> ''
    ) tmp
    WHERE duplicate_rank > 1
);

DELETE FROM player_contributions
WHERE player_id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY account_id
                   ORDER BY
                       CASE WHEN last_login_date IS NULL THEN 1 ELSE 0 END ASC,
                       last_login_date DESC,
                       id DESC
               ) AS duplicate_rank
        FROM players
        WHERE account_id IS NOT NULL AND account_id <> ''
    ) tmp
    WHERE duplicate_rank > 1
);

DELETE FROM players
WHERE id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY account_id
                   ORDER BY
                       CASE WHEN last_login_date IS NULL THEN 1 ELSE 0 END ASC,
                       last_login_date DESC,
                       id DESC
               ) AS duplicate_rank
        FROM players
        WHERE account_id IS NOT NULL AND account_id <> ''
    ) tmp
    WHERE duplicate_rank > 1
);

UPDATE players
SET account_id = NULL
WHERE account_id = '';

CREATE UNIQUE INDEX players_account_id_nonempty_unique
    ON players (account_id);
