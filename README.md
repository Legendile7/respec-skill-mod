# Respec Skill Mod
Reset and reallocate your skills used in [Pufferfish's Skills](https://modrinth.com/mod/skills) easily!

**⚠️ THIS MOD REQUIRES CONFIGURATION TO WORK!**

# Features

**Skill Respec System:** Reset individual skill categories and get back XP to reallocate

**Prestige System:** Transition from one skill tree to another at high levels

**Configurable:** Set minimum levels and XP retention rates

# How It Works
### 1. Craft a **Respec Scroll**
Use the following recipe:

![Respec Scroll Crafting Recipe](https://cdn.modrinth.com/data/cached_images/68beb43e27e38c317879286b21e66b20498de157.png)

### 2. Build a **Skill Altar**
Create an altar by placing a configured skill block with a lodestone on top.

Here’s an example setup for the [Default Skill Tree](https://modrinth.com/datapack/default-skill-trees):
![Default Skill Tree Skill Altars](https://cdn.modrinth.com/data/cached_images/5ed253ed7fb86a670ca34a380505cb3c123ed14f.png)

### 3. Reset your skills!
Simply right-click the lodestone with your Respec Scroll to reset your skill points.

### 4. Prestige System
When you reach high levels in a skill category, you can transition to advanced skill trees through the prestige system. Configure prestige mappings to define which skills can transition to which advanced trees, along with requirements and XP transfer rates.

# Configuration

### Basic Settings
- `min_level_to_respec`: Minimum level required to use respec (default: 20)
- `xp_reduction_factor`: Percentage of XP retained after respec (default: 0.2 = 20%)

### Skill Altar Mappings
Define which blocks under a lodestone correspond to which skill categories. This allows you to create themed altars for different skills.

### Prestige System
(Optional) Configure skill tree transitions for advanced progression:
- `from_skill` → `to_skill`: Define which skill can transition to which advanced tree
- `enabled`: Whether this prestige path is active
- `min_prestige_level`: Minimum level required for prestige (overrides `min_level_to_respec`)
- `xp_transfer_rate`: How much XP transfers to the new skill tree (overrides `xp_reduction_factor`)

# For Servers/Modpacks
The mod runs server-side. To see the Respec Scroll in the creative inventory or through mods like [REI](https://modrinth.com/mod/rei), it must also be installed client-side.

Feel free to include this mod in your modpacks! Credit is appreciated but not mandatory.

# Default Config
<details>
<summary>Default Config</summary>

```toml
# Respec Skill Mod Configuration
# This file allows you to customize the behavior of the respec skill mod
# Config Version: 2
config_version = 2

# Minimum level required to respec a skill category
min_level_to_respec = 20

# Factor by which XP is reduced after respec (0.2 = 20% of original XP retained)
xp_reduction_factor = 0.2

# Skill Altar Mappings
# Define which blocks (under a lodestone) correspond to which skill categories
# Format: "minecraft:block_name" = "skill_category_name"
[skill_altar_map]
"minecraft:diamond_block" = "combat"
"minecraft:iron_block" = "mining"

# Prestige System Mappings
# Define skill tree transitions for prestige system
# Format: "from_skill" = "to_skill,enabled,min_prestige_level,xp_reduction_factor"
# Example: "combat" = "advanced_combat,true,50,0.4"
[prestige_mappings]
# "combat" = "advanced_combat,true,50,0.4"
# "mining" = "master_mining,true,75,0.6"
```

</details>

