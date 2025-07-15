package com.respecskill;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.puffish.skillsmod.api.Category;
import net.puffish.skillsmod.api.SkillsAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class RespecConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(RespecMod.MOD_ID);
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("respec-skill.toml");

    // Using fallback default, will be detected when needed
    private String skillTreeNamespace = "puffish_skills";
    private int minLevelToRespec = 20;
    private float xpReductionFactor = 0.2f;
    private final Map<Block, String> skillAltarMap = new HashMap<>();

    // Track if we've attempted detection yet
    private boolean hasAttemptedDetection = false;

    public RespecConfig() {
        // Default skill altar mappings
        skillAltarMap.put(Blocks.DIAMOND_BLOCK, "combat");
        skillAltarMap.put(Blocks.IRON_BLOCK, "mining");
    }

    /**
     * Auto-detects and sets the namespace, logging the result.
     * Only attempts detection if the mod is fully loaded.
     */
    public void detectAndSetNamespace() {
        try {
            this.skillTreeNamespace = detectSkillTreeNamespace();
            this.hasAttemptedDetection = true;
            LOGGER.info("Using skill tree namespace: {}", this.skillTreeNamespace);
        } catch (Exception e) {
            LOGGER.warn("Failed to detect skill tree namespace: {}. Will retry later.", e.getMessage());
            this.hasAttemptedDetection = false;
        }
    }

    public void load() {
        if (!Files.exists(CONFIG_PATH)) {
            save();
            return;
        }

        try {
            String content = Files.readString(CONFIG_PATH);
            parseTOML(content);
            LOGGER.info("Config loaded successfully");
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Failed to load config: {}", e.getMessage());
        }
    }

    private void parseTOML(String content) {
        // Simple TOML parser for our specific needs
        String[] lines = content.split("\n");
        boolean inSkillAltarMap = false;

        for (String line : lines) {
            line = line.trim();

            // Skip comments and empty lines
            if (line.startsWith("#") || line.isEmpty()) {
                continue;
            }

            // Check for section headers
            if (line.equals("[skill_altar_map]")) {
                inSkillAltarMap = true;
                skillAltarMap.clear(); // Clear defaults when loading from config
                continue;
            } else if (line.startsWith("[")) {
                inSkillAltarMap = false;
                continue;
            }

            // Parse key-value pairs
            if (line.contains("=")) {
                String[] parts = line.split("=", 2);
                String key = parts[0].trim();
                String value = parts[1].trim();

                if (inSkillAltarMap) {
                    // Parse skill altar mapping
                    String blockId = key.replace("\"", "");
                    String skillName = value.replace("\"", "");

                    try {
                        Identifier blockIdentifier = Identifier.of(blockId);
                        Block block = Registries.BLOCK.get(blockIdentifier);
                        skillAltarMap.put(block, skillName);
                        LOGGER.info("Loaded skill altar mapping: {} -> {}", blockId, skillName);
                    } catch (Exception e) {
                        LOGGER.error("Error loading skill altar mapping for {}: {}", blockId, e.getMessage());
                    }
                } else {
                    // Parse general config values
                    switch (key) {
                        case "min_level_to_respec":
                            minLevelToRespec = Integer.parseInt(value);
                            break;
                        case "xp_reduction_factor":
                            xpReductionFactor = Float.parseFloat(value);
                            break;
                    }
                }
            }
        }
    }

    public void save() {
        StringBuilder toml = new StringBuilder();

        // Add header comment
        toml.append("# Respec Skill Mod Configuration\n");
        toml.append("# This file allows you to customize the behavior of the respec skill mod\n\n");

        // Basic settings
        toml.append("# Minimum level required to respec a skill category\n");
        toml.append("min_level_to_respec = ").append(minLevelToRespec).append("\n\n");

        toml.append("# Factor by which XP is reduced after respec (0.2 = 20% of original XP retained)\n");
        toml.append("xp_reduction_factor = ").append(xpReductionFactor).append("\n\n");

        // Skill altar mappings
        toml.append("# Skill Altar Mappings\n");
        toml.append("# Define which blocks (under a lodestone) correspond to which skill categories\n");
        toml.append("# Format: \"minecraft:block_name\" = \"skill_category_name\"\n");
        toml.append("[skill_altar_map]\n");

        for (Map.Entry<Block, String> entry : skillAltarMap.entrySet()) {
            Block block = entry.getKey();
            String skillName = entry.getValue();

            Identifier blockId = Registries.BLOCK.getId(block);
            toml.append("\"").append(blockId).append("\" = \"").append(skillName).append("\"\n");
        }

        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, toml.toString());
            LOGGER.info("Config saved to {}", CONFIG_PATH);
        } catch (IOException e) {
            LOGGER.error("Failed to save config: {}", e.getMessage());
        }
    }

    /**
     * Attempts to auto-detect the skill tree namespace by querying all available categories
     * from the Puffish Skills API.
     *
     * @return The detected namespace, or "puffish_skills" as fallback if none found
     */
    private String detectSkillTreeNamespace() {
        try {
            // Get all registered categories using streamCategories()
            Collection<Category> categories = SkillsAPI.streamCategories().toList();

            // If there are no categories, use a sensible default
            if (categories.isEmpty()) {
                LOGGER.warn("No skill categories found, using fallback namespace: puffish_skills");
                return "puffish_skills"; // Common default
            }

            // Create a map to count namespaces
            Map<String, Integer> namespaceCount = new HashMap<>();

            // Count occurrences of each namespace
            for (Category category : categories) {
                Identifier id = category.getId();
                String namespace = id.getNamespace();

                namespaceCount.put(namespace, namespaceCount.getOrDefault(namespace, 0) + 1);
            }

            // Find the most common namespace
            String detectedNamespace = namespaceCount.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("puffish_skills"); // Fallback

            LOGGER.info("Detected skill tree namespace: {} (from {} categories)",
                    detectedNamespace, namespaceCount.getOrDefault(detectedNamespace, 0));

            return detectedNamespace;
        } catch (Exception e) {
            LOGGER.error("Error detecting namespace: {}", e.getMessage());
            return "puffish_skills"; // Fallback
        }
    }

    public String getSkillTreeNamespace() {
        // Try to detect the namespace if we haven't already
        if (!hasAttemptedDetection) {
            detectAndSetNamespace();
        }
        return skillTreeNamespace;
    }

    public int getMinLevelToRespec() {
        return minLevelToRespec;
    }

    public float getXpReductionFactor() {
        return xpReductionFactor;
    }

    public Map<Block, String> getSkillAltarMap() {
        return skillAltarMap;
    }
}