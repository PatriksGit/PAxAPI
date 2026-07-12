# Config Write-Back (`ConfigWriter`) — Design Spec

## Motivation

`ConfigFile` (`hu.patriksgit.paxapi.config`) is deliberately read-only — deep-immutable maps/lists,
no set/save method anywhere. That's correct for its job (parsing the human-edited `config.yml`),
but two PAxAPI consumers have each hand-rolled their own persistence layer to work around it,
because they need to write a value back to disk at runtime:

1. **PAxAuth (Paper)** — `PaperConfig.saveAuthSpawn(Location)`: builds a Bukkit `YamlConfiguration`,
   `set()`s a few keys, writes to a tmp file, then `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`
   into a dedicated `auth-spawn.yml` (never `config.yml` itself).
2. **PAxAuth (Velocity)** — `MaintenanceConfig.write()`: fully hand-rolled, raw SnakeYAML
   `Yaml`/`DumperOptions`, explicit `write()` method, same tmp-file + `ATOMIC_MOVE` pattern, into
   its own `maintenance.yml`.

Both independently reinvented the same atomic-write plumbing. Neither writes into the
human-edited `config.yml` — intentional in both cases: `config.yml` is comment-heavy, and a plain
YAML dump would silently strip every comment on save. The proven pattern is: a separate,
machine-managed side-file, with atomic persistence, never a round-trip rewrite of the human-edited
config.

**New evidence — a third, structurally different use case:** a future plugin (PAxPvpManager) needs
a `/scan` command that discovers Multiverse-Core worlds and, for each one not already known, adds a
per-world settings block (enderpearl cooldown/distance, crystal/anchor explosion behavior, totem
toggle) to a machine-managed `worlds.yml`, with sane defaults. Re-running `/scan` after a new world
appears must NOT clobber already-customized per-world blocks — only backfill missing ones. That's
an idempotent upsert-missing-keys operation, a shape the two existing PAxAuth call sites never
needed.

## Scope

A new class, `ConfigWriter`, alongside `ConfigFile` — **not** a method bolted onto `ConfigFile`,
which must stay immutable. Covers both proven shapes:

1. **Flat set + atomic persist** — replaces the hand-rolled tmp-file + `ATOMIC_MOVE` dance with a
   single call.
2. **Idempotent section upsert** — given discovered keys and default values/blocks, write only the
   ones not already present on disk; leave existing customized blocks untouched.

`ConfigWriter` fully replaces `saveAuthSpawn` and the header + atomic-save part of
`MaintenanceConfig.write()` (via the `headerComment` parameter, see below). It does **not** cover
`MaintenanceConfig.write()`'s dump-*after* conditional placeholder-comment text
(`if (bypassRoles.isEmpty()) { w.write("bypass-roles:\n  # - manager\n  # - team\n") }`) — no
footer/trailer parameter is added for this; it would be the same kind of speculative,
single-call-site API surface this design avoids elsewhere (no section-path, no wrapper type, no
pool-size heuristic). PAxAuth's own migration drops this cosmetic hint, most simply by writing
`bypass-roles: []` explicitly into the map instead of omitting the key when empty (functionally
equivalent; only the inline example-comment hint is lost).

## Package

`hu.patriksgit.paxapi.config` (alongside `ConfigFile`, `ConfigException`).

## API

```java
package hu.patriksgit.paxapi.config;

public final class ConfigWriter {
    private ConfigWriter() {}

    public static void save(Path file, Map<String, Object> data) throws IOException { ... }
    public static void save(Path file, Map<String, Object> data, String headerComment) throws IOException { ... }

    public static Set<String> upsertMissing(Path file, Map<String, Object> candidates, Logger logger) throws IOException { ... }
}
```

### `save(file, data)`

The caller passes the **complete** desired document — this dumps it wholesale as YAML
(`DumperOptions.FlowStyle.BLOCK` + `prettyFlow(true)`, matching `MaintenanceConfig.write()`'s
existing convention) to a sibling `.tmp` file, then
`Files.move(tmp, file, ATOMIC_MOVE, REPLACE_EXISTING)`. **Full overwrite, not a partial merge** —
this is exactly what both existing call sites already do (they always reconstruct and write their
complete known state). Creates the parent directory if missing.

### `save(file, data, headerComment)`

Same as above, plus: `headerComment` is written as raw text into the `.tmp` file immediately
before the YAML dump. Replaces the hand-rolled "preserve header comment" trick in
`MaintenanceConfig.write()`. `headerComment` is validated with
`Objects.requireNonNull(headerComment, "headerComment")` — passing `null` throws
`NullPointerException`. This keeps the two overloads' purposes sharply separated: the 2-arg form
means "never a header"; the 3-arg form means "I have a real header to write" — no silent
null-means-same-as-the-other-overload equivalence.

### `upsertMissing(file, candidates, logger)`

Loads the file if it exists (starts from an empty map if it doesn't — no bundled-default
extraction, unlike `ConfigFile.load`). For each key in `candidates` that is **not yet present**
in the loaded map, adds it; keys already present are left **untouched regardless of their value or
shape** (presence-only decision, no type/shape validation against the candidate).

**Presence check is `containsKey`, never `get(key) != null`.** An existing key explicitly mapped to
YAML `null` still counts as "present" and must not be overwritten — `putIfAbsent`/
`computeIfAbsent`-style shortcuts are explicitly disallowed as the implementation strategy because
both treat an existing `null` value as absent, which would silently violate this contract. The
implementation must use a plain explicit loop:

```java
for (Map.Entry<String, Object> e : candidates.entrySet()) {
    if (!loaded.containsKey(e.getKey())) {
        loaded.put(e.getKey(), e.getValue());
        added.add(e.getKey());
    }
}
```

**`candidates` values must be non-null.** Unlike the general "any YAML-serializable type" contract
for `save()`'s `data` map, a `null` candidate *value* is out of contract for `upsertMissing` — no
real call site has ever needed it (every known candidate is a real default block), so it is
deliberately excluded from the supported type set rather than adding a speculative capability with
its own footgun (`computeIfAbsent(k, key -> candidates.get(key))` would silently skip a `null`
candidate value and under-report the `added` set). No explicit runtime rejection is added for this
either — it's simply undocumented/unsupported, consistent with the library's existing style of not
guarding against every hypothetical internal misuse.

If any key was added, persists the merged map via `save(file, mergedMap)` (internal reuse — no
duplicated write logic). **If nothing was added, no write happens at all** — no tmp file, no move,
the original file's mtime is untouched. Returns the set of keys actually added, as a
`LinkedHashSet` preserving `candidates`' iteration order (deterministic for admin-facing feedback
like "added: nether, the_end").

### Reading in `upsertMissing` (no `ConfigFile` dependency)

A small private parse method: `Yaml(new SafeConstructor(new LoaderOptions()))`. Returns a
**mutable** `LinkedHashMap` — does *not* go through `ConfigFile.deepImmutable`. `ConfigFile.java`
is untouched by this feature (chosen over extracting a shared parse helper: the ~15-line overlap
with `ConfigFile.readYaml` is small and stable, and duplicating it keeps zero regression risk on
the existing, already-shipped, 76-test-covered `ConfigFile.java`).

- Malformed YAML (`YAMLException`) → `ConfigException("Malformed YAML in " + file + " (see cause for details)")` — matches `ConfigFile.readYaml`'s wording exactly.
- Existing file's root is not a mapping → `ConfigException("Config file root must be a YAML mapping: " + file)` — matches `ConfigFile.readYaml`'s wording exactly.
- **Existing file present but empty** (parses to `null`) → `logger.warn(...)`, then treated as an
  empty map and the upsert proceeds as if the file didn't exist. This is the **sole** call site for
  the `logger` parameter — mirrors `ConfigFile.readYaml`'s identical behavior for the identical
  structural case (`config.yml` present but empty). It is not used for the "existing key present
  but different shape than candidate" case (that stays silent/untouched by design), nor for the
  malformed-YAML or non-mapping-root cases (those throw, they don't log-and-continue).

## Error handling

- `Objects.requireNonNull` on every parameter (`file`, `data`/`candidates`, `logger`, and
  `headerComment` on the 3-arg `save` overload) — `NullPointerException`, consistent with the
  `CooldownTracker`/`ManagedExecutors` convention.
- YAML-shape errors on read (malformed YAML, non-mapping root) → `ConfigException` (extends
  `IOException`).
- Raw I/O failures (disk full, permission denied) propagate unwrapped.
- No internal concurrency guard — callers issuing concurrent writes to the same file must
  serialize themselves (e.g. via their own `synchronized` block, as `MaintenanceConfig`'s runtime
  state already does today).

## Out of scope / non-goals

- No comment-preserving in-place `config.yml` editing (explicit non-goal).
- No section-path/dot-notation support in `upsertMissing` — root-level keys only. No known use
  case needs nesting; `ConfigFile.section()`-style scoping can be added later if one appears.
- No footer/trailer-comment parameter on `save()` (see Scope section above).
- `upsertMissing` does not accept a `headerComment` — a future caller needing one can assemble the
  merged map itself and call `save(file, data, headerComment)` directly.
- `candidates` values must be non-null (see `upsertMissing` section above) — not a supported input.

## Testing plan — `ConfigWriterTest`

**`save(file, data)` / `save(file, data, headerComment)`**
- `savesFlatMapAndReadsBackViaConfigFile` — write a map, then `ConfigFile.load(file, logger)` it and
  confirm values are readable (round-trip compatibility with `ConfigFile`'s parser).
- `saveOverwritesExistingFileCompletely` — write map A, then write map B (missing one of A's keys)
  → reading back, A's now-absent key is gone (full overwrite, not merge — sharply distinguished
  from `upsertMissing`).
- `saveIsAtomicNoTmpFileLeftBehind` — after `save()`, the sibling `.tmp` file does not exist.
- `saveCreatesParentDirectoriesIfMissing` — target's parent directory doesn't exist yet → `save()`
  creates it.
- `saveWithHeaderCommentPrependsRawTextBeforeYaml` — using the header overload, the raw text
  appears verbatim before the YAML content in the written file.
- `saveWithHeaderCommentRejectsNullHeaderComment` — `save(file, data, null)` → `NullPointerException`.
- `saveRejectsNullFile` / `saveRejectsNullData` — `NullPointerException`.

**`upsertMissing(file, candidates, logger)`**
- `upsertMissingCreatesFileWhenAbsentWithAllCandidates` — file doesn't exist → all candidates
  written, returned set equals all candidate keys.
- `upsertMissingAddsOnlyTrulyMissingKeysAndLeavesExistingBlocksUntouched` — pre-written file has one
  key manually customized (a value different from what the same key's candidate default would be),
  plus one new candidate key → the existing key's on-disk value is untouched, the new key is added
  with its candidate value, returned set equals only the new key.
- `upsertMissingTreatsExistingNullValueAsPresentNotMissing` — pre-written file has `key: null`;
  `candidates` maps that same key to a real, non-null default → asserts the on-disk value for that
  key remains `null` (untouched), and the returned set does **not** contain that key. This is the
  regression guard against a `putIfAbsent`-style implementation, which would incorrectly treat the
  existing `null` as absent and overwrite it.
- `upsertMissingReturnsEmptySetAndDoesNotWriteWhenNothingToAdd` — every candidate key already
  present → empty returned set, and no write occurs at all (verify via unchanged file
  last-modified-time, or that no `.tmp` file is ever created).
- `upsertMissingSupportsNestedStructuredValues` — a candidate value is itself a nested `Map`
  (multiple typed fields, like a per-world block) → after adding, reading back via `ConfigFile`
  confirms the nested fields are reachable.
- `upsertMissingLogsWarnAndTreatsExistingEmptyFileAsEmptyMap` — a pre-written, empty/whitespace-only
  file → the logger receives a `warn` call (Mockito `verify`), and the upsert proceeds as if the
  file didn't exist (all candidates get added).
- `upsertMissingThrowsConfigExceptionOnMalformedYaml` — invalid YAML in the file → `ConfigException`.
- `upsertMissingThrowsConfigExceptionWhenRootIsNotAMapping` — the file's root is a list/scalar →
  `ConfigException`.
- `upsertMissingPreservesCandidateIterationOrderInAddedSet` — multiple missing keys, a
  `LinkedHashMap` candidates argument → the returned set iterates in the same order as `candidates`
  was given.
- `upsertMissingRejectsNullFile` / `RejectsNullCandidates` / `RejectsNullLogger` —
  `NullPointerException`.

## Affected files

| File | Change |
|---|---|
| `src/main/java/hu/patriksgit/paxapi/config/ConfigWriter.java` | New. |
| `src/test/java/hu/patriksgit/paxapi/config/ConfigWriterTest.java` | New. |
| `API.md` | New section documenting `ConfigWriter`. |
| `ConfigFile.java` | Untouched (deliberate — see Reading section above). |
