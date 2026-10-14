-- Fleet-wide trusted IPs of a privileged player, read by essential.common.permission.AdminTrust.
-- The boot's column repair adds this column before Flyway runs, so the guard makes this a no-op there;
-- written down so the schema history says when it arrived.
ALTER TABLE players ADD COLUMN IF NOT EXISTS admin_trust TEXT;
