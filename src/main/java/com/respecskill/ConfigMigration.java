package com.respecskill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class ConfigMigration {
    private static final Logger LOGGER = LoggerFactory.getLogger(RespecMod.MOD_ID);

    // Version identifiers in config
    private static final String CONFIG_VERSION_KEY = "config_version";
    private static final int CURRENT_CONFIG_VERSION = 2;
    private static final int LEGACY_CONFIG_VERSION = 1;

    /**
     * Checks if the config needs migration and performs it if necessary
     * @param configPath Path to the config file
     * @return true if migration was performed, false otherwise
     */
    public static boolean migrateConfigIfNeeded(Path configPath) {
        if (!Files.exists(configPath)) {
            LOGGER.info("No existing config found, will create new one");
            return false;
        }

        try {
            String content = Files.readString(configPath);
            int currentVersion = detectConfigVersion(content);

            if (currentVersion < CURRENT_CONFIG_VERSION) {
                LOGGER.info("Detected old config version ({}), migrating to version {}",
                        currentVersion, CURRENT_CONFIG_VERSION);

                // Create backup of old config
                createBackup(configPath);

                // Perform migration
                String migratedContent = migrateContent(content, currentVersion);

                // Write migrated config
                Files.writeString(configPath, migratedContent);

                LOGGER.info("Config migration completed successfully");
                return true;
            } else {
                LOGGER.debug("Config is already up to date (version {})", currentVersion);
                return false;
            }

        } catch (IOException e) {
            LOGGER.error("Failed to migrate config: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Detects the version of the config file
     */
    private static int detectConfigVersion(String content) {
        // Check if config_version is explicitly set
        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith(CONFIG_VERSION_KEY + " =")) {
                try {
                    String version = line.split("=", 2)[1].trim();
                    return Integer.parseInt(version);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid config version format, assuming legacy");
                    return LEGACY_CONFIG_VERSION;
                }
            }
        }

        // If no version is found, check for prestige mappings section
        if (content.contains("[prestige_mappings]")) {
            return 2; // Has prestige system
        } else {
            return 1; // Legacy version without prestige system
        }
    }

    /**
     * Creates a backup of the current config file
     */
    private static void createBackup(Path configPath) {
        try {
            Path backupPath = configPath.resolveSibling(configPath.getFileName() + ".backup");
            Files.copy(configPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Created config backup at: {}", backupPath);
        } catch (IOException e) {
            LOGGER.warn("Failed to create config backup: {}", e.getMessage());
        }
    }

    /**
     * Migrates config content from old version to new version
     */
    private static String migrateContent(String oldContent, int fromVersion) {
        StringBuilder newContent = new StringBuilder();

        // Add header with version
        newContent.append("# Respec Skill Mod Configuration\n");
        newContent.append("# This file allows you to customize the behavior of the respec skill mod\n");
        newContent.append("# Config Version: ").append(CURRENT_CONFIG_VERSION).append("\n");
        newContent.append(CONFIG_VERSION_KEY).append(" = ").append(CURRENT_CONFIG_VERSION).append("\n\n");

        // Extract existing values
        int minLevel = extractIntValue(oldContent, "min_level_to_respec", 20);
        float xpReduction = extractFloatValue(oldContent, "xp_reduction_factor", 0.2f);

        // Add basic settings
        newContent.append("# Minimum level required to respec a skill category\n");
        newContent.append("min_level_to_respec = ").append(minLevel).append("\n\n");

        newContent.append("# Factor by which XP is reduced after respec (0.2 = 20% of original XP retained)\n");
        newContent.append("xp_reduction_factor = ").append(xpReduction).append("\n\n");

        // Add skill altar mappings
        newContent.append("# Skill Altar Mappings\n");
        newContent.append("# Define which blocks (under a lodestone) correspond to which skill categories\n");
        newContent.append("# Format: \"minecraft:block_name\" = \"skill_category_name\"\n");
        newContent.append("[skill_altar_map]\n");

        // Parse and preserve existing skill altar mappings
        String[] lines = oldContent.split("\n");
        boolean inSkillAltarMap = false;

        for (String line : lines) {
            String trimmedLine = line.trim();

            if (trimmedLine.equals("[skill_altar_map]")) {
                inSkillAltarMap = true;
                continue;
            } else if (inSkillAltarMap && trimmedLine.startsWith("[")) {
                inSkillAltarMap = false;
                break;
            }

            // Copy skill altar mappings
            if (inSkillAltarMap && !trimmedLine.isEmpty() && !trimmedLine.startsWith("#") && trimmedLine.contains("=")) {
                newContent.append(line).append("\n");
            }
        }

        // Add prestige mappings section (new in v2)
        if (fromVersion < 2) {
            newContent.append("\n");
            addPrestigeMappings(newContent);
        }

        return newContent.toString();
    }

    // This method is no longer needed - removed to clean up code

    /**
     * Adds the new prestige mappings section
     */
    private static void addPrestigeMappings(StringBuilder content) {
        content.append("# Prestige System Mappings\n");
        content.append("# Define skill tree transitions for prestige system\n");
        content.append("# Format: \"from_skill\" = \"to_skill,enabled,min_prestige_level,xp_reduction_factor\"\n");
        content.append("# Example: \"combat\" = \"advanced_combat,true,50,0.4\"\n");
        content.append("[prestige_mappings]\n");
        content.append("# \"combat\" = \"advanced_combat,true,50,0.4\"\n");
        content.append("# \"mining\" = \"master_mining,true,75,0.6\"\n");
    }

    /**
     * Extracts an integer value from the config content
     */
    private static int extractIntValue(String content, String key, int defaultValue) {
        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith(key + " =")) {
                try {
                    String value = line.split("=", 2)[1].trim();
                    return Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Failed to parse {} value, using default", key);
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    /**
     * Extracts a float value from the config content
     */
    private static float extractFloatValue(String content, String key, float defaultValue) {
        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith(key + " =")) {
                try {
                    String value = line.split("=", 2)[1].trim();
                    return Float.parseFloat(value);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Failed to parse {} value, using default", key);
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }
}