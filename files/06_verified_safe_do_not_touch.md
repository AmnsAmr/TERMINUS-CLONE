# Terminus Handoff — Verified Safe (Do Not "Fix" These)

> Reference file, not a task list. A key part of the original audit was
> recording things that *look* dangerous but were actually confirmed safe,
> so no future agent wastes time or introduces risk "fixing" them. If your
> own changes elsewhere make one of these newly relevant (e.g. you change
> the data feeding into it), re-verify before assuming it's still fine.

## UI / Compose

- Nested `LazyColumn` in Now Playing is bounded by a 300dp height (not a
  performance risk).
- Other `items(key = ...)` sites besides the Stats SONG tab bug were
  checked individually and are safe.
- Home's `getYourMix` uses `.distinct()`.
- Library grouping keys were checked and are fine.
- Album/artist/playlist list keys were checked and are fine.

## Charts

- `AreaChart.kt` guards `coords.first()/last()` with a `points.size < 2`
  check.
- `LineChart.kt` does the same.
- `PieChart.kt` protects against zero totals.
- `BlockSeekBar.kt` guards its divisors.
- `ScatterPlot.kt` guards its divisors.
- `BlockyBarChart.kt` guards its divisor/max cases.

(Note: `LineChart`'s `gridLines` divide-by-zero and negative-value
handling in Line/Area/Scatter/Pie/BlockyBarChart are *not* on this safe
list — those are real risks tracked in
`04_ui_animation_and_navigation.md`, item 9.)

## Nullability / casts

- No `!!` operators anywhere in app source.
- No unchecked casts anywhere in app source.
- Lyrics parsing uses safe casts.
- Metadata extras access is null-safe.

## Navigation

- Album route encoding correctly handles `/`, spaces, and the `+` round
  trip.
- Current route wiring supplies the expected arguments under normal use.
  (The defensive-parsing recommendation in
  `04_ui_animation_and_navigation.md` item 10 is about *future* robustness
  under restored/malformed state, not a currently-reachable bug.)

## MediaStore

- The permission gate prevents the specific pre-permission
  `SecurityException` path identified during the audit.
- The remaining MediaStore issue is column availability
  (`03_database_and_reliability.md` item 8), not the permission gate
  itself.

## Other

- `importAudioFiles()` correctly treats null streams as failed copies.
- `queryDisplayName()` checks its cursor column before use.
- `SystemInfoCard` sentinel handling is safe.
- `TerminalNavIcon` uses an exhaustive `when`, so it will fail to compile
  (not crash at runtime) if the enum grows without being handled — this is
  the desired behavior, not a bug.
