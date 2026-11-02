# BENCH_RESULT: test-harness fixes (changeMap, menu-id observables)

## What changed and why

- `Essential/src/test/kotlin/PluginTest.kt` (shared harness), new helpers:
  - `changeMap(args, by = player)`: runs `/changemap <args>`, then re-adds to `Groups.player` only the
    players that were in `Groups.player` before the call (the set `WorldReloader.begin()` saves), never
    every row of `players` (that would make earlier tests' ghosts visible, the correction noted in
    `still-open-wave4.md`). It asserts every saved player is back in `Groups.player`.
    A one-off probe run showed it does real work: `saved=2 missing=2`, `saved=5 missing=5`,
    `saved=10 missing=10` on FeatureTest's three map changes (probe removed afterwards).
  - `menuListenerCount()`: the engine menu registry size (reflection on `Menus.menuListeners`; moved
    from `OwnedMenuTest`).
  - `menuRegisteredBy(what, block)`: snapshots the registry, runs the block, returns the first new id,
    fails by name if the block registered nothing. Replaces `Menus.registerMenu { _, _ -> } - 1`, which
    guessed "the last registered menu" and leaked a listener on every read.
  - `ownedMenuOpenedBy(what, block)`: the `OwnedMenus` allocation-based helper that was copied four times
    (UndoTest, InfoMenuTest, PagingMenuStalenessTest, OwnedMenuTest). Now one copy.
- `FeatureTest.kt`: the three `/changemap Glacier pvp` calls use `changeMap`; the hub-zone menu, the
  difficulty menu and the rating menu are addressed through `menuRegisteredBy`; the two "no new menu"
  counts use `menuListenerCount()` instead of probe registrations.
- `UndoTest.kt`, `InfoMenuTest.kt`, `PagingMenuStalenessTest.kt`, `OwnedMenuTest.kt`: local copies of the
  helper deleted, shared one imported; unused imports removed.
- `CoreEvent.kt`: comment only (it cited `FeatureTest.kt:1451` and the old `- 1` read). No behaviour change.

## Verify commands (JDK 21, `JAVA_HOME=C:\Users\mky\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2`)

Waited for another session's Gradle test worker to finish before the first run; one Gradle run at a time.

- `./gradlew :Essential:test --tests 'FeatureTest' --tests 'UndoTest' --tests 'InfoMenuTest' --tests 'PagingMenuStalenessTest' --tests 'OwnedMenuTest' --console=plain --no-daemon`
  BUILD SUCCESSFUL. FeatureTest 26/26, UndoTest 12/12, InfoMenuTest 3/3, PagingMenuStalenessTest 1/1, OwnedMenuTest 4/4.
- `./gradlew :Essential:test --tests 'FeatureTest' ...` with the temporary probe: 26/26, probe output above.
- Flake runs on the final code (the repo has no flake-runs script; ran 3x with `--rerun` so Gradle does not skip):
  `./gradlew :Essential:test --rerun --tests 'FeatureTest' --tests 'UndoTest' --tests 'InfoMenuTest' --tests 'PagingMenuStalenessTest' --tests 'OwnedMenuTest' --console=plain --no-daemon`
  3 of 3 runs: BUILD SUCCESSFUL, 46/46 tests, 0 failures, 0 errors.
- After the runs, one KDoc sentence was added to FeatureTest's `pvpBalanceTest` comment (comment only, not re-run).

## Not done / uncertain

- Full `:Essential:test` not run (CLAUDE.md: end-of-work only, heavy, other sessions on the box).
  Effect on other classes in a full run is not checked; the class-entry reset should still clean up.
- `ClientCommandTest.client_changemap` and `WorldHistoryScopeTest` still change maps by raw command. Left
  on purpose: they test `/changemap` itself, and neither needs the players back (`client_changemap` already
  creates a new player after each change).
- The rating menus are `lazy` singletons: `menuRegisteredBy` sees them only in the first block that touches
  them. Today that is `onlyThePlayerShownTheRatingMenuMayRateTheMap`; if an earlier test ever reaches them,
  that test now fails by name instead of clicking a wrong id. For the same reason the "no rating menu for a
  player who left" test cannot see a menu if it runs after the rating test (it was like that before).
- `.fleet/backlog-2026-09-10/still-open-wave4.md` not updated to mark the two items done.
- No production bug found.
