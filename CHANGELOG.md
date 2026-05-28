# Changelog

## 2.0.0

A ground-up rewrite. The altar/scroll mechanic from 1.x is **removed entirely**; respec now runs through a `/respec` command. Old config files (`respec-skill.toml`) are ignored — a fresh `respec-skill.properties` is written on first launch.

### Game/Loader support
- Updated to Minecraft **26.1.2**

### Player-facing changes
- **New:** `/respec` opens a chat-clickable menu listing every Puffish skill category loaded on the server.
- **New:** `/respec <namespace:category>` performs the respec directly (used by the menu's `[Reset]` button).
- **New:** Every Puffish category is auto-discovered from `SkillsAPI.streamCategories()` — admins no longer have to list categories in the config.
- **New:** Per-respec cost is paid from the player's inventory (default: 16 emeralds; configurable).
- **New:** Optional per-player cooldown (`cooldown_seconds`, in-memory only — resets on server restart).
- **Removed:** Respec Scroll item, the recipe, the lodestone-on-base-block altar mechanic, and the `UseBlockCallback` handler.

### Prestige
- One-way ascensions from one category into another. Configure with `prestige.<n> = <from>, <to>, <min_points>, <xp_carryover_factor>, <cost_item>, <cost_count>`.
- `/respec prestige` lists configured paths (ineligible ones are shown greyed out with current vs required points).
- `/respec prestige <from_category>` executes the ascension: source category is erased, target category gains `currentXp × xp_carryover_factor`, cost is consumed.
- When any prestige path is configured, `/respec` links to the prestige menu at the bottom of its output.

### Migration from 1.x
- Delete (or ignore) the old `respec-skill.toml`. A new `respec-skill.properties` will be generated on first launch.
- Tell players the altar/scroll is gone — they use `/respec` now.
- If you were using prestige in 1.x, port the mappings to the new schema (see Prestige section in the README). 1.x prestige used `from_skill` (path only); 2.x uses full `namespace:path` identifiers.

---

## 1.x Legacy

See the "1.x Legacy" section in the README for documentation of the old altar/scroll mechanic.
