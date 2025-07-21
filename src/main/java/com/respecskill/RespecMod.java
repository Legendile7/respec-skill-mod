package com.respecskill;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemGroups;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;

import java.util.*;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.puffish.skillsmod.api.Experience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.component.type.NbtComponent;

import net.minecraft.util.Identifier;
import net.puffish.skillsmod.api.Category;
import net.puffish.skillsmod.api.SkillsAPI;


public class RespecMod implements ModInitializer {

	public static final String MOD_ID = "respec-skill";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static RespecConfig config;

	@Override
	public void onInitialize() {
		LOGGER.info("Respec Skill Mod Initialized!");

		// Initialize and load config
		config = new RespecConfig();
		config.load();

		registerEventListeners();
		registerCommands();
		registerServerStartedCallback();

		ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(content -> {
			// 1. Create the ItemStack instance
			ItemStack respecScroll = new ItemStack(Items.PAPER);

			// 2. Create the custom data NBT
			NbtCompound customNbt = new NbtCompound();
			customNbt.putBoolean("respec_scroll", true);

			// 3. Apply all components to the ItemStack with the fixes
			respecScroll.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Respec Scroll").formatted(Formatting.GOLD));
			respecScroll.set(DataComponentTypes.LORE, new LoreComponent(List.of(
					// Correct way to apply multiple formats
					Text.literal("A mystic parchment, shimmering with potential.").formatted(Formatting.GRAY, Formatting.ITALIC),
					Text.literal("Use it at a skill altar to rewrite your path.").formatted(Formatting.DARK_GRAY, Formatting.ITALIC)
			)));
			respecScroll.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(customNbt));
			respecScroll.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);

			// 4. Add the finished item to the creative tab
			content.add(respecScroll);

		});
	}
	/**
	 * Register a callback for when the server is fully started,
	 * which is a safe time to detect the skill tree namespace
	 */
	private void registerServerStartedCallback() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			LOGGER.info("Server started, detecting skill tree namespace...");
			config.detectAndSetNamespace();
		});
	}


	private void registerEventListeners() {
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (world.isClient()) {
				return ActionResult.PASS;
			}

			BlockPos pos = hitResult.getBlockPos();
			Block clickedBlock = world.getBlockState(pos).getBlock();

			if (clickedBlock != Blocks.LODESTONE) {
				return ActionResult.PASS;
			}

			ItemStack heldItemStack = player.getStackInHand(hand);

			if (!isRespecScroll(heldItemStack)) {
				return ActionResult.PASS;
			}

			BlockPos baseBlockPos = pos.down();
			Block baseBlock = world.getBlockState(baseBlockPos).getBlock();

			Map<Block, String> skillAltarMap = config.getSkillAltarMap();
			if (skillAltarMap.containsKey(baseBlock)) {
				String skillCategory = skillAltarMap.get(baseBlock);
				executeRespec((ServerPlayerEntity) player, world, hand, pos, skillCategory);
				return ActionResult.SUCCESS;
			} else {
				player.sendMessage(Text.literal("This Lodestone is not properly attuned for a skill reset.").formatted(Formatting.RED), false);
				return ActionResult.FAIL;
			}
		});
	}

	/**
	 * Checks if the given ItemStack is a valid "Respec Scroll" by checking for our custom component.
	 * @param stack The ItemStack to check.
	 * @return true if the item has the RESPEC_SCROLL component set to true.
	 */
	private boolean isRespecScroll(ItemStack stack) {
		if (!stack.isOf(Items.PAPER)) {
			return false;
		}

		// 1. Get the NbtComponent from the item's custom data.
		NbtComponent customNbt = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (customNbt == null) {
			return false;
		}

		// 2. Get the actual NbtCompound from the NbtComponent.
		NbtCompound nbt = customNbt.copyNbt();

		// 3. Check for your tag, same as before.
		return nbt.getBoolean("respec_scroll").orElse(false);
	}


	private void executeRespec(ServerPlayerEntity player, World world, Hand hand, BlockPos pos, String skillCategory) {
		// Make sure namespace is detected
		String namespace = config.getSkillTreeNamespace();

		// Create a proper namespace:path identifier for the source
		Identifier categoryId = Identifier.of(namespace, skillCategory);

		// Try to get total points instead of source-specific points
		Optional<Category> categoryOpt = SkillsAPI.getCategory(categoryId);
		if (categoryOpt.isPresent()) {
			Category category = categoryOpt.get();
			int points = category.getPointsTotal(player);

			// Check if prestige system is enabled for this skill category
			Optional<RespecConfig.PrestigeMapping> prestigeOpt = config.getPrestigeMapping(skillCategory);

			if (prestigeOpt.isPresent() && prestigeOpt.get().isEnabled()) {
				// Prestige system is enabled for this skill
				executePrestigeRespec(player, world, hand, pos, skillCategory, prestigeOpt.get(), category, points);
			} else {
				// Regular respec
				executeRegularRespec(player, world, hand, pos, skillCategory, category, points);
			}
		} else {
			// Try to detect namespace again, maybe it changed
			config.detectAndSetNamespace();
			namespace = config.getSkillTreeNamespace();

			// Try again with the newly detected namespace
			categoryId = Identifier.of(namespace, skillCategory);
			categoryOpt = SkillsAPI.getCategory(categoryId);

			if (categoryOpt.isPresent()) {
				Category category = categoryOpt.get();
				int points = category.getPointsTotal(player);

				// Check if prestige system is enabled for this skill category
				Optional<RespecConfig.PrestigeMapping> prestigeOpt = config.getPrestigeMapping(skillCategory);

				if (prestigeOpt.isPresent() && prestigeOpt.get().isEnabled()) {
					// Prestige system is enabled for this skill
					executePrestigeRespec(player, world, hand, pos, skillCategory, prestigeOpt.get(), category, points);
				} else {
					// Regular respec
					executeRegularRespec(player, world, hand, pos, skillCategory, category, points);
				}
			} else {
				player.sendMessage(Text.literal("Skill category '" + skillCategory + "' not found in config. Please notify server administrators.").formatted(Formatting.RED), false);
			}
		}
	}

	private void executeRegularRespec(ServerPlayerEntity player, World world, Hand hand, BlockPos pos, String skillCategory, Category category, int points) {
		// If the player has points, reset their skills (using config for minimum level)
		if (points >= config.getMinLevelToRespec()) {
			player.getStackInHand(hand).decrement(1);

			Optional<Experience> experienceOpt = category.getExperience();

			if (experienceOpt.isPresent()) {
				Experience experience = experienceOpt.get();
				int currentXp = experience.getTotal(player);
				LOGGER.info("Player has {} XP in {} category", currentXp, skillCategory);

				// Erase category
				category.erase(player);

				// Calculate new XP based on the config factor
				int newXP = (int)(currentXp * config.getXpReductionFactor());

				// If there was XP and we should give some back
				if (newXP > 0) {
					// Construct and execute the command to add back reduced XP
					String xpCommand = String.format("/puffish_skills experience add %s %s:%s %d",
							player.getName().getString(),
							config.getSkillTreeNamespace(),
							skillCategory,
							newXP);

					LOGGER.info("Executing XP command: {}", xpCommand);

					Objects.requireNonNull(player.getServer()).getCommandManager().executeWithPrefix(
							player.getServer().getCommandSource().withSilent().withLevel(4),
							xpCommand
					);

					LOGGER.info("Added back {} XP to player for {}:{} category", newXP,
							config.getSkillTreeNamespace(), skillCategory);
				}

				int newLevel = experience.getLevel(player);

				// Effects
				createRespecEffects(player, world, pos);

				player.sendMessage(Text.literal("Your " + skillCategory + " skills have been reset!").formatted(Formatting.GOLD), false);
				player.sendMessage(Text.literal("You are now level " + newLevel).formatted(Formatting.AQUA), false);
				player.sendMessage(Text.literal("A surge of power washes over you...").formatted(Formatting.GRAY), false);
			}
		} else {
			player.sendMessage(Text.literal("You need to be level " + config.getMinLevelToRespec() +
					" in this category to respec!").formatted(Formatting.RED), false);
		}
	}

	private void executePrestigeRespec(ServerPlayerEntity player, World world, Hand hand, BlockPos pos, String skillCategory, RespecConfig.PrestigeMapping prestige, Category category, int points) {
		// Check if player meets prestige requirements
		if (points < prestige.getMinPrestigeLevel()) {
			player.sendMessage(Text.literal("You need to be level " + prestige.getMinPrestigeLevel() +
					" in " + skillCategory + " to prestige to " + prestige.getToSkill() + "!").formatted(Formatting.RED), false);
			return;
		}

		player.getStackInHand(hand).decrement(1);

		Optional<Experience> experienceOpt = category.getExperience();

		if (experienceOpt.isPresent()) {
			Experience experience = experienceOpt.get();
			int currentXp = experience.getTotal(player);
			LOGGER.info("Player has {} XP in {} category, prestiging to {}", currentXp, skillCategory, prestige.getToSkill());

			// Erase the original category's data to prevent XP duplication.
			category.erase(player);
			LOGGER.info("Erased player data for original category: {}", skillCategory);

			// Lock the current skill tree first
			String lockCommand = String.format("/puffish_skills category lock %s %s:%s",
					player.getName().getString(),
					config.getSkillTreeNamespace(),
					skillCategory);

			LOGGER.info("Executing lock command: {}", lockCommand);

			Objects.requireNonNull(player.getServer()).getCommandManager().executeWithPrefix(
					player.getServer().getCommandSource().withSilent().withLevel(4),
					lockCommand
			);

			// Unlock the new skill tree
			String unlockCommand = String.format("/puffish_skills category unlock %s %s:%s",
					player.getName().getString(),
					config.getSkillTreeNamespace(),
					prestige.getToSkill());

			LOGGER.info("Executing unlock command: {}", unlockCommand);

			Objects.requireNonNull(player.getServer()).getCommandManager().executeWithPrefix(
					player.getServer().getCommandSource().withSilent().withLevel(4),
					unlockCommand
			);

			// Calculate prestige XP based on the prestige multiplier
			int prestigeXP = (int)(currentXp * prestige.getPrestigeXpMultiplier());

			// Add XP to the NEW skill tree instead of the old one
			if (prestigeXP > 0) {
				String xpCommand = String.format("/puffish_skills experience add %s %s:%s %d",
						player.getName().getString(),
						config.getSkillTreeNamespace(),
						prestige.getToSkill(),
						prestigeXP);

				LOGGER.info("Executing prestige XP command: {}", xpCommand);

				Objects.requireNonNull(player.getServer()).getCommandManager().executeWithPrefix(
						player.getServer().getCommandSource().withSilent().withLevel(4),
						xpCommand
				);

				LOGGER.info("Added {} XP to player for {}:{} category (prestige)", prestigeXP,
						config.getSkillTreeNamespace(), prestige.getToSkill());
			}

			// Get the new experience level for feedback
			Identifier newCategoryId = Identifier.of(config.getSkillTreeNamespace(), prestige.getToSkill());
			Optional<Category> newCategoryOpt = SkillsAPI.getCategory(newCategoryId);
			int newLevel = 0;
			if (newCategoryOpt.isPresent()) {
				Optional<Experience> newExperienceOpt = newCategoryOpt.get().getExperience();
				if (newExperienceOpt.isPresent()) {
					newLevel = newExperienceOpt.get().getLevel(player);
				}
			}

			// Enhanced prestige effects
			createPrestigeEffects(player, world, pos);

			player.sendMessage(Text.literal("PRESTIGE ASCENSION COMPLETE!").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD), false);
			player.sendMessage(Text.literal("Your " + skillCategory + " mastery has evolved into " + prestige.getToSkill() + "!").formatted(Formatting.GOLD), false);
			player.sendMessage(Text.literal("You are now level " + newLevel + " in " + prestige.getToSkill()).formatted(Formatting.AQUA), false);
			player.sendMessage(Text.literal("The power of transcendence flows through you...").formatted(Formatting.DARK_PURPLE, Formatting.ITALIC), false);
		}
	}

	private void createRespecEffects(ServerPlayerEntity player, World world, BlockPos pos) {
		LightningEntity lightning = new LightningEntity(EntityType.LIGHTNING_BOLT, world);
		lightning.setCosmetic(true);
		lightning.setPosition(pos.toCenterPos().add(0, 0.5, 0));
		world.spawnEntity(lightning);

		world.playSound(null, pos, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.BLOCKS, 1.0f, 1.0f);
	}

	private void createPrestigeEffects(ServerPlayerEntity player, World world, BlockPos pos) {
		// Multiple lightning strikes for prestige
		for (int i = 0; i < 3; i++) {
			LightningEntity lightning = new LightningEntity(EntityType.LIGHTNING_BOLT, world);
			lightning.setCosmetic(true);
			lightning.setPosition(pos.toCenterPos().add(
					(world.getRandom().nextDouble() - 0.5) * 2,
					0.5 + i * 0.3,
					(world.getRandom().nextDouble() - 0.5) * 2
			));
			world.spawnEntity(lightning);
		}

		world.playSound(null, pos, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.BLOCKS, 1.5f, 0.8f);
		world.playSound(null, pos, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.BLOCKS, 0.7f, 1.2f);
	}

	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(
					CommandManager.literal("respecskill")
							.requires(source -> source.hasPermissionLevel(3)) // Requires permission level 3 (operator)
							.then(CommandManager.literal("reload")
									.executes(context -> {
										// Reload the config and auto-detect namespace
										config.load();
										context.getSource().sendFeedback(
												() -> Text.literal("Respec Skill config reloaded successfully!")
														.formatted(Formatting.GREEN),
												true
										);

										// Report the namespace being used
										context.getSource().sendFeedback(
												() -> Text.literal("Using skill tree namespace: " + config.getSkillTreeNamespace())
														.formatted(Formatting.YELLOW),
												true
										);

										return Command.SINGLE_SUCCESS;
									})
							)
							.then(CommandManager.literal("prestige")
									.then(CommandManager.literal("lock")
											.then(CommandManager.argument("player", StringArgumentType.word())
													.then(CommandManager.argument("skill", StringArgumentType.word())
															.executes(context -> {
																String playerName = StringArgumentType.getString(context, "player");
																String skillCategory = StringArgumentType.getString(context, "skill");

																// Execute the lock command
																String lockCommand = String.format("/puffish_skills category lock %s %s:%s",
																		playerName,
																		config.getSkillTreeNamespace(),
																		skillCategory);

																LOGGER.info("Manual lock command: {}", lockCommand);

																Objects.requireNonNull(context.getSource().getServer()).getCommandManager().executeWithPrefix(
																		context.getSource().getServer().getCommandSource().withSilent().withLevel(4),
																		lockCommand
																);

																context.getSource().sendFeedback(
																		() -> Text.literal("Locked skill tree " + skillCategory + " for player " + playerName)
																				.formatted(Formatting.RED),
																		true
																);

																return Command.SINGLE_SUCCESS;
															})
													)
											)
									)
									.then(CommandManager.literal("unlock")
											.then(CommandManager.argument("player", StringArgumentType.word())
													.then(CommandManager.argument("skill", StringArgumentType.word())
															.executes(context -> {
																String playerName = StringArgumentType.getString(context, "player");
																String skillCategory = StringArgumentType.getString(context, "skill");

																// Execute the unlock command
																String unlockCommand = String.format("/puffish_skills category unlock %s %s:%s",
																		playerName,
																		config.getSkillTreeNamespace(),
																		skillCategory);

																LOGGER.info("Manual unlock command: {}", unlockCommand);

																Objects.requireNonNull(context.getSource().getServer()).getCommandManager().executeWithPrefix(
																		context.getSource().getServer().getCommandSource().withSilent().withLevel(4),
																		unlockCommand
																);

																context.getSource().sendFeedback(
																		() -> Text.literal("Unlocked skill tree " + skillCategory + " for player " + playerName)
																				.formatted(Formatting.GREEN),
																		true
																);

																return Command.SINGLE_SUCCESS;
															})
													)
											)
									)
									.then(CommandManager.literal("add_xp")
											.then(CommandManager.argument("player", StringArgumentType.word())
													.then(CommandManager.argument("skill", StringArgumentType.word())
															.then(CommandManager.argument("amount", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
																	.executes(context -> {
																		String playerName = StringArgumentType.getString(context, "player");
																		String skillCategory = StringArgumentType.getString(context, "skill");
																		int xpAmount = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "amount");

																		// Execute the XP add command
																		String xpCommand = String.format("/puffish_skills experience add %s %s:%s %d",
																				playerName,
																				config.getSkillTreeNamespace(),
																				skillCategory,
																				xpAmount);

																		LOGGER.info("Manual XP command: {}", xpCommand);

																		Objects.requireNonNull(context.getSource().getServer()).getCommandManager().executeWithPrefix(
																				context.getSource().getServer().getCommandSource().withSilent().withLevel(4),
																				xpCommand
																		);

																		context.getSource().sendFeedback(
																				() -> Text.literal("Added " + xpAmount + " XP to " + skillCategory + " for player " + playerName)
																						.formatted(Formatting.AQUA),
																				true
																		);

																		return Command.SINGLE_SUCCESS;
																	})
															)
													)
											)
									)
									.then(CommandManager.literal("info")
											.executes(context -> {
												context.getSource().sendFeedback(
														() -> Text.literal("=== Respec Skill Prestige System ===").formatted(Formatting.GOLD, Formatting.BOLD),
														false
												);

												context.getSource().sendFeedback(
														() -> Text.literal("Available Commands:").formatted(Formatting.YELLOW),
														false
												);

												context.getSource().sendFeedback(
														() -> Text.literal("• /respecskill prestige lock <player> <skill> - Lock a skill tree").formatted(Formatting.WHITE),
														false
												);

												context.getSource().sendFeedback(
														() -> Text.literal("• /respecskill prestige unlock <player> <skill> - Unlock a skill tree").formatted(Formatting.WHITE),
														false
												);

												context.getSource().sendFeedback(
														() -> Text.literal("• /respecskill prestige add_xp <player> <skill> <amount> - Add XP to skill").formatted(Formatting.WHITE),
														false
												);

												context.getSource().sendFeedback(
														() -> Text.literal("Current namespace: " + config.getSkillTreeNamespace()).formatted(Formatting.GRAY),
														false
												);

												// Show configured prestige mappings
												Map<String, RespecConfig.PrestigeMapping> mappings = config.getPrestigeMappings();
												if (!mappings.isEmpty()) {
													context.getSource().sendFeedback(
															() -> Text.literal("Configured Prestige Mappings:").formatted(Formatting.YELLOW),
															false
													);

													for (Map.Entry<String, RespecConfig.PrestigeMapping> entry : mappings.entrySet()) {
														RespecConfig.PrestigeMapping mapping = entry.getValue();
														String status = mapping.isEnabled() ? "ENABLED" : "DISABLED";
														Formatting statusColor = mapping.isEnabled() ? Formatting.GREEN : Formatting.RED;

														context.getSource().sendFeedback(
																() -> Text.literal("  • " + entry.getKey() + " → " + mapping.getToSkill() +
																				" (Min: " + mapping.getMinPrestigeLevel() +
																				", XP: " + mapping.getPrestigeXpMultiplier() + "x) [" + status + "]")
																		.formatted(Formatting.WHITE)
																		.append(Text.literal(" " + status).formatted(statusColor)),
																false
														);
													}
												} else {
													context.getSource().sendFeedback(
															() -> Text.literal("No prestige mappings configured.").formatted(Formatting.GRAY),
															false
													);
												}

												return Command.SINGLE_SUCCESS;
											})
									)
							)
			);
		});
	}
}