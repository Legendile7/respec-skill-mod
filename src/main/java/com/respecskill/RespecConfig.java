package com.respecskill;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

public class RespecConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(RespecMod.MOD_ID);
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("respec-skill.properties");

    public record Cost(Item item, int count) {}
    public record PrestigeMapping(Identifier from, Identifier to, int minPoints, float xpCarryover, Cost cost) {}

    private int minLevelToRespec = 20;
    private float xpReductionFactor = 0.2f;
    private int cooldownSeconds = 0;
    private Cost defaultCost = new Cost(Items.EMERALD, 16);
    private final Map<Identifier, Cost> costOverrides = new HashMap<>();
    private final List<PrestigeMapping> prestigeMappings = new ArrayList<>();

    public void load() {
        if (!Files.exists(CONFIG_PATH)) {
            writeDefaults();
            return;
        }
        try {
            Properties props = new Properties();
            props.load(new StringReader(Files.readString(CONFIG_PATH)));

            if (props.containsKey("min_level_to_respec"))
                minLevelToRespec = Integer.parseInt(props.getProperty("min_level_to_respec").trim());
            if (props.containsKey("xp_reduction_factor"))
                xpReductionFactor = Float.parseFloat(props.getProperty("xp_reduction_factor").trim());
            if (props.containsKey("cooldown_seconds"))
                cooldownSeconds = Integer.parseInt(props.getProperty("cooldown_seconds").trim());
            if (props.containsKey("default_cost")) {
                Cost c = parseCost(props.getProperty("default_cost").trim());
                if (c != null) defaultCost = c;
            }

            costOverrides.clear();
            for (String k : props.stringPropertyNames()) {
                if (!k.startsWith("cost.")) continue;
                Identifier categoryId = Identifier.tryParse(k.substring("cost.".length()));
                Cost c = parseCost(props.getProperty(k).trim());
                if (categoryId == null || c == null) {
                    LOGGER.warn("Skipping malformed override: {} = {}", k, props.getProperty(k));
                    continue;
                }
                costOverrides.put(categoryId, c);
            }

            prestigeMappings.clear();
            TreeSet<String> prestigeKeys = new TreeSet<>();
            for (String k : props.stringPropertyNames()) {
                if (k.startsWith("prestige.")) prestigeKeys.add(k);
            }
            for (String key : prestigeKeys) {
                PrestigeMapping pm = parsePrestige(key, props.getProperty(key).trim());
                if (pm != null) prestigeMappings.add(pm);
            }

            LOGGER.info("Config loaded ({} cost overrides, {} prestige paths; default cost {} x{})",
                    costOverrides.size(), prestigeMappings.size(),
                    BuiltInRegistries.ITEM.getKey(defaultCost.item()), defaultCost.count());
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Failed to load config, keeping defaults: {}", e.getMessage());
        }
    }

    private static Cost parseCost(String value) {
        String[] parts = value.split(",");
        if (parts.length != 2) return null;
        Identifier itemId = Identifier.tryParse(parts[0].trim());
        if (itemId == null) return null;
        Item item = BuiltInRegistries.ITEM.getValue(itemId);
        if (item == Items.AIR) return null;
        try {
            int count = Integer.parseInt(parts[1].trim());
            if (count <= 0) return null;
            return new Cost(item, count);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static PrestigeMapping parsePrestige(String key, String value) {
        String[] parts = value.split(",");
        if (parts.length != 6) {
            LOGGER.warn("Skipping prestige {} — expected 6 fields (from, to, min_points, xp_factor, cost_item, cost_count), got {}", key, parts.length);
            return null;
        }
        Identifier from = Identifier.tryParse(parts[0].trim());
        Identifier to = Identifier.tryParse(parts[1].trim());
        Identifier itemId = Identifier.tryParse(parts[4].trim());
        if (from == null || to == null || itemId == null) {
            LOGGER.warn("Skipping prestige {} — invalid identifier", key);
            return null;
        }
        Item item = BuiltInRegistries.ITEM.getValue(itemId);
        try {
            int minPoints = Integer.parseInt(parts[2].trim());
            float factor = Float.parseFloat(parts[3].trim());
            int count = Integer.parseInt(parts[5].trim());
            if (item == Items.AIR || count <= 0 || minPoints < 0) {
                LOGGER.warn("Skipping prestige {} — bad item / non-positive count / negative min_points", key);
                return null;
            }
            return new PrestigeMapping(from, to, minPoints, factor, new Cost(item, count));
        } catch (NumberFormatException e) {
            LOGGER.warn("Skipping prestige {} — number parse error", key);
            return null;
        }
    }

    private void writeDefaults() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Respec Skill Mod Configuration\n");
        sb.append("# All Puffish skill categories are auto-discovered — you do NOT need to list them.\n");
        sb.append("# Reload in-game with /respec reload (requires permission level 3).\n\n");

        sb.append("# Minimum total skill points a player must have in a category before they can respec it.\n");
        sb.append("min_level_to_respec = ").append(minLevelToRespec).append("\n\n");

        sb.append("# Fraction of category XP returned after respec (0.0 = lose all, 1.0 = lose none).\n");
        sb.append("xp_reduction_factor = ").append(xpReductionFactor).append("\n\n");

        sb.append("# Cooldown in seconds between respecs (0 = no cooldown). Per player, in-memory only.\n");
        sb.append("cooldown_seconds = ").append(cooldownSeconds).append("\n\n");

        sb.append("# Default cost applied to every category that doesn't have an override below.\n");
        sb.append("# Format:  <item_id>, <count>\n");
        sb.append("default_cost = ").append(BuiltInRegistries.ITEM.getKey(defaultCost.item())).append(", ").append(defaultCost.count()).append("\n\n");

        sb.append("# Per-category cost overrides (optional). Examples:\n");
        sb.append("# cost.puffish_skills:combat = minecraft:diamond, 4\n");
        sb.append("# cost.puffish_skills:mining = minecraft:emerald, 32\n\n");

        sb.append("# Prestige paths (optional, one-way irreversible ascensions).\n");
        sb.append("# Format:  prestige.<n> = <from_category>, <to_category>, <min_points_in_from>, <xp_carryover_factor>, <cost_item>, <cost_count>\n");
        sb.append("# After prestige: source category is erased; target category gains currentXp * xp_carryover_factor.\n");
        sb.append("# Use /respec prestige to list paths and /respec prestige <from_category> to ascend.\n");
        sb.append("# Examples:\n");
        sb.append("# prestige.1 = puffish_skills:combat, puffish_skills:advanced_combat, 50, 0.4, minecraft:netherite_ingot, 1\n");
        sb.append("# prestige.2 = puffish_skills:mining, puffish_skills:master_mining, 75, 0.6, minecraft:netherite_ingot, 2\n");

        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, sb.toString());
            LOGGER.info("Wrote default config to {}", CONFIG_PATH);
        } catch (IOException e) {
            LOGGER.error("Failed to write default config: {}", e.getMessage());
        }
    }

    public int getMinLevelToRespec() { return minLevelToRespec; }
    public float getXpReductionFactor() { return xpReductionFactor; }
    public int getCooldownSeconds() { return cooldownSeconds; }
    public List<PrestigeMapping> getPrestigeMappings() { return prestigeMappings; }

    public Cost costFor(Identifier categoryId) {
        return costOverrides.getOrDefault(categoryId, defaultCost);
    }

    public PrestigeMapping prestigeFrom(Identifier fromCategoryId) {
        for (PrestigeMapping pm : prestigeMappings) {
            if (pm.from().equals(fromCategoryId)) return pm;
        }
        return null;
    }
}
