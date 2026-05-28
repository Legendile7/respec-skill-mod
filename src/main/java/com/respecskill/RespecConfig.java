package com.respecskill;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

public class RespecConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(RespecMod.MOD_ID);
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("respec-skill.properties");

    private int minLevelToRespec = 20;
    private float xpReductionFactor = 0.2f;
    private Block altarTopBlock = Blocks.LODESTONE;
    private final Map<Block, Identifier> altars = new LinkedHashMap<>();

    public RespecConfig() {
        altars.put(Blocks.DIAMOND_BLOCK, Identifier.fromNamespaceAndPath("puffish_skills", "combat"));
        altars.put(Blocks.IRON_BLOCK, Identifier.fromNamespaceAndPath("puffish_skills", "mining"));
    }

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
            if (props.containsKey("altar_top_block")) {
                Block top = lookupBlock(props.getProperty("altar_top_block").trim());
                if (top != null) altarTopBlock = top;
            }

            Map<Block, Identifier> parsed = new LinkedHashMap<>();
            TreeSet<String> altarKeys = new TreeSet<>();
            for (String k : props.stringPropertyNames()) {
                if (k.startsWith("altar.")) altarKeys.add(k);
            }
            for (String key : altarKeys) {
                String value = props.getProperty(key).trim();
                String[] parts = value.split(",", 2);
                if (parts.length != 2) {
                    LOGGER.warn("Skipping malformed altar entry {} = {} (expected 'block,category')", key, value);
                    continue;
                }
                Block block = lookupBlock(parts[0].trim());
                Identifier category = Identifier.tryParse(parts[1].trim());
                if (block == null || category == null) {
                    LOGGER.warn("Skipping invalid altar entry {} = {}", key, value);
                    continue;
                }
                parsed.put(block, category);
            }
            if (!parsed.isEmpty()) {
                altars.clear();
                altars.putAll(parsed);
            }

            LOGGER.info("Config loaded ({} altars)", altars.size());
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Failed to load config, keeping defaults: {}", e.getMessage());
        }
    }

    private void writeDefaults() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Respec Skill Mod Configuration\n");
        sb.append("# All values are reloadable in-game with /respecskill reload\n\n");

        sb.append("# Minimum total skill points a player must have in a category to be allowed to respec it.\n");
        sb.append("min_level_to_respec = ").append(minLevelToRespec).append("\n\n");

        sb.append("# Fraction of category XP returned after a respec (0.0 = lose everything, 1.0 = lose nothing).\n");
        sb.append("xp_reduction_factor = ").append(xpReductionFactor).append("\n\n");

        sb.append("# The block placed ON TOP of the altar base. Right-clicking this with a Respec Scroll triggers a reset.\n");
        sb.append("altar_top_block = ").append(BuiltInRegistries.BLOCK.getKey(altarTopBlock)).append("\n\n");

        sb.append("# Altar mappings.\n");
        sb.append("# Format:  altar.<n> = <base_block_id>,<skill_category_id>\n");
        sb.append("# The base block is the one directly UNDER the altar top block.\n");
        sb.append("# The skill category is the full Puffish identifier (namespace:path).\n");
        int i = 1;
        for (Map.Entry<Block, Identifier> e : altars.entrySet()) {
            sb.append("altar.").append(i++).append(" = ")
                    .append(BuiltInRegistries.BLOCK.getKey(e.getKey())).append(",")
                    .append(e.getValue()).append("\n");
        }

        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, sb.toString());
            LOGGER.info("Wrote default config to {}", CONFIG_PATH);
        } catch (IOException e) {
            LOGGER.error("Failed to write default config: {}", e.getMessage());
        }
    }

    private static Block lookupBlock(String id) {
        Identifier blockId = Identifier.tryParse(id);
        return blockId == null ? null : BuiltInRegistries.BLOCK.getValue(blockId);
    }

    public int getMinLevelToRespec() { return minLevelToRespec; }
    public float getXpReductionFactor() { return xpReductionFactor; }
    public Block getAltarTopBlock() { return altarTopBlock; }
    public Map<Block, Identifier> getAltars() { return altars; }
}
