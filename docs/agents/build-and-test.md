# Build and test details

## Scratch PostgreSQL cluster (for `LivePostgresUpgradeTest`)

PostgreSQL 17 binaries: `C:\Program Files\PostgreSQL\17\bin`. The user's own service `postgresql-x64-17` is stopped: leave it alone.

```
initdb -D <scratch>/pgdata -U postgres --pwfile=<file containing: essential> -E UTF8 --locale=C -A scram-sha-256
pg_ctl -D <scratch>/pgdata -o "-p 5433 -c listen_addresses=127.0.0.1" -l <scratch>/pg.log start
```

Stop it with `pg_ctl -D <scratch>/pgdata stop` when done. Without it the PG tests skip.

## Verify a branch against clean main

`git worktree add ../Essentials-clean <ref>` and run the suite there. A class-order repro can use timestamps in `Essential/build/test-results/test/TEST-*.xml`.
Remove stale worktrees with /makarasty-tools:prune; never `git worktree remove` or delete the folder (it follows a junction and empties the main checkout).

## Jar provenance

- `:Essential:shadowJar` with JDK 21 is reproducible: entry timestamps are fixed, so the sha256 of a rebuild from a clean detached worktree of the commit equals the deployed jar.
- Put the worktree at a short path (for example `%TEMP%\claude\essb`).
- Jars contain duplicate `META-INF/LICENSE*` entries: use `unzip -o`.

## Test harness facts

- Test byproducts `Essential/mods/` and `Essential/locales` are gitignored.
- `FeatureTest.pvpBalanceTest` and `ClientCommandTest` in one JVM: `client_gg` fails on leftover pvp state. Both pass alone.
- Verifying a full-suite failure: re-run the class alone; `--rerun` the full suite once before calling it a regression.
