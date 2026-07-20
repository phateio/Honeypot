# Honeypot

A Paper plugin for catching griefers with honeypot blocks: mark blocks or
regions as honeypots, and players who break them accumulate offense points.
Reaching the threshold rolls back everything they broke or placed, cancels the
event, punishes them (ban / kick / custom console commands), and optionally
broadcasts the catch.

This is a from-scratch rewrite of the original
[Honeypot](https://github.com/andune/Honeypot) plugin for modern Paper
(26.2+, Java 25), written against a behavior specification. It contains no
code from the original project.

## How it works

- Honeypots are **named** and each holds any mix of individually marked blocks
  and cuboid regions — a whole structure (say `beacon_2024`) is one honeypot.
  The name is stored as data, so it survives every save and shows up in
  `/hp list`. Names are purely organizational; points always accumulate per
  player across all honeypots.
- Breaking a honeypot block earns points (`offense-point-map`, default 1 per
  block). At `offense-points` total, the player's breaks and placements are
  rolled back and the configured `action` runs. Points intentionally never
  reset while the server runs, so a kicked player who returns and breaks
  another honeypot block trips it again immediately.
- Blocks broken below the threshold are restored when the player trips the
  honeypot, stays logged out for over 300 seconds, or the server stops.
- Placements inside honeypots by players without `honeypot.place` are recorded
  and removed on rollback, so a statue can't be altered by filling it in.

## Commands

`/honeypot` (alias `/hp`), permission `honeypot.create`:

| Command | Effect |
|---------|--------|
| `/hp` | Toggle selection mode: right-click a block with an empty main hand to mark it into the active honeypot. |
| `/hp create <name>` | Create/select the active honeypot that marks and regions go into. |
| `/hp pos1 [x y z]` / `/hp pos2 [x y z]` | Set region corners — the targeted block (within 10 blocks, else the block underfoot) or explicit coordinates. |
| `/hp region` | Add a region from pos1/pos2 (same world) to the active honeypot. |
| `/hp list [name]` | List honeypots with their counts, or one honeypot's regions and blocks. |
| `/hp delete <name> [n]` | Delete a whole honeypot, or just its region number `n`. |
| `/hp save` | Save honeypot data to disk. |
| `/hp reload` | Reload config and honeypot data. |

Marks made without an active honeypot go into a pot named `default`. Individual
marks are removed by breaking the block while holding the `honeypot.break`
permission (which also makes you immune to honeypots).

## Permissions

| Permission | Default | Effect |
|------------|---------|--------|
| `honeypot.create` | op | Manage honeypots (command and selection mode). |
| `honeypot.break` | op | Break honeypot blocks without penalty; removes the mark. |
| `honeypot.place` | op | Place blocks inside honeypots without being recorded. |

## Data and migration

Honeypot data lives in `plugins/Honeypot/honeypots.yml`, keyed by honeypot name:

```yaml
honeypots:
  beacon_2024:
    regions:
    - world_2024,-473,71,-865,-465,75,-857
    blocks:
    - world_2024,-465,73,-861
  spawn_2024:
    blocks:
    - world_2024,-481,70,-841
```

It is saved whenever it changes (marking, unmarking, region/pot add/remove) —
never on a plain shutdown. On first start, if `honeypots.yml` is absent and a
legacy `list.ncsv` (from the original plugin) exists, it is imported into the
`default` honeypot and renamed to `list.ncsv.imported`. A pre-2.1 flat
`blocks:`/`regions:` file is read and rewritten into the `default` honeypot.
Legacy config files are not migrated — start from the generated `config.yml`.

Catches are logged to the console and, with `log-to-file: true`, appended to
`logs/honeypot.log` (next to Paper's own latest.log, where the original
plugin also wrote).

## Building

```sh
./gradlew build
```

The jar lands in `build/libs/`. Requires JDK 25.
