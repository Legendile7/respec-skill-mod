package com.respecskill;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemGroups;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import java.util.List;
import com.mojang.serialization.Codec;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.component.ComponentType;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.nbt.NbtCompound;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class RespecMod implements ModInitializer {

	public static final String MOD_ID = "respec-skill";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final Map<Block, String> SKILL_ALTAR_MAP = new HashMap<>();

	@Override
	public void onInitialize() {
		LOGGER.info("Respec Skill Mod Initialized!");
		// Note: The component is already registered via the static initializer above.

		SKILL_ALTAR_MAP.put(Blocks.DIAMOND_BLOCK, "combat_skills");
		SKILL_ALTAR_MAP.put(Blocks.EMERALD_BLOCK, "exploration_skills");
		SKILL_ALTAR_MAP.put(Blocks.REDSTONE_BLOCK, "utility_skills");
		SKILL_ALTAR_MAP.put(Blocks.GOLD_BLOCK, "gathering_skills");

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

			registerEventListeners();
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

			// --- FIX 2: The check is now much cleaner and more reliable ---
			if (!isRespecScroll(heldItemStack)) {
				return ActionResult.PASS;
			}

			BlockPos baseBlockPos = pos.down();
			Block baseBlock = world.getBlockState(baseBlockPos).getBlock();

			if (SKILL_ALTAR_MAP.containsKey(baseBlock)) {
				String skillCategory = SKILL_ALTAR_MAP.get(baseBlock);
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
		player.getStackInHand(hand).decrement(1);

		String command = String.format("/yourskillmod command reset %s %s", player.getName().getString(), skillCategory);

		Objects.requireNonNull(player.getServer()).getCommandManager().executeWithPrefix(
				player.getServer().getCommandSource().withSilent().withLevel(4).withEntity(player),
				command
		);

		LightningEntity lightning = new LightningEntity(EntityType.LIGHTNING_BOLT, world);
		lightning.setCosmetic(true);
		lightning.setPosition(pos.toCenterPos().add(0, 0.5, 0));
		world.spawnEntity(lightning);

		world.playSound(null, pos, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.BLOCKS, 1.0f, 1.0f);

		player.sendMessage(Text.literal("Your " + skillCategory.replace("_", " ") + " have been reset!").formatted(Formatting.GOLD), false);
		player.sendMessage(Text.literal("A surge of power washes over you...").formatted(Formatting.GRAY), false);
	}
}