package com.respecskill;

import com.mojang.brigadier.Command;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.puffish.skillsmod.api.Category;
import net.puffish.skillsmod.api.Experience;
import net.puffish.skillsmod.api.SkillsAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

public class RespecMod implements ModInitializer {

    public static final String MOD_ID = "respec-skill";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static RespecConfig config;

    @Override
    public void onInitialize() {
        LOGGER.info("Respec Skill Mod Initialized!");

        config = new RespecConfig();
        config.load();

        registerEventListeners();
        registerCommands();
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                LOGGER.info("Respec Skill ready ({} altars configured)", config.getAltars().size()));
    }

    private static ItemStack createRespecScroll() {
        ItemStack stack = new ItemStack(Items.PAPER);

        CompoundTag customNbt = new CompoundTag();
        customNbt.putBoolean("respec_scroll", true);

        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Respec Scroll").withStyle(ChatFormatting.GOLD));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("A mystic parchment, shimmering with potential.").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC),
                Component.literal("Use it at a skill altar to rewrite your path.").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC)
        )));
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(customNbt));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    private void registerEventListeners() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;

            BlockPos pos = hitResult.getBlockPos();
            if (level.getBlockState(pos).getBlock() != config.getAltarTopBlock()) return InteractionResult.PASS;

            ItemStack held = serverPlayer.getItemInHand(hand);
            if (!isRespecScroll(held)) return InteractionResult.PASS;

            Block baseBlock = level.getBlockState(pos.below()).getBlock();
            Identifier categoryId = config.getAltars().get(baseBlock);
            if (categoryId == null) {
                serverPlayer.sendSystemMessage(Component.literal("This altar is not properly attuned for a skill reset.").withStyle(ChatFormatting.RED));
                return InteractionResult.FAIL;
            }

            executeRespec(serverPlayer, level, hand, pos, categoryId);
            return InteractionResult.SUCCESS;
        });
    }

    private boolean isRespecScroll(ItemStack stack) {
        if (!stack.is(Items.PAPER)) return false;
        CustomData customNbt = stack.get(DataComponents.CUSTOM_DATA);
        if (customNbt == null) return false;
        return customNbt.copyTag().getBooleanOr("respec_scroll", false);
    }

    private void executeRespec(ServerPlayer player, Level level, InteractionHand hand, BlockPos pos, Identifier categoryId) {
        Optional<Category> categoryOpt = SkillsAPI.getCategory(categoryId);
        if (categoryOpt.isEmpty()) {
            player.sendSystemMessage(Component.literal("Skill category '" + categoryId + "' not found. Notify server admins.").withStyle(ChatFormatting.RED));
            return;
        }
        Category category = categoryOpt.get();

        if (category.getPointsTotal(player) < config.getMinLevelToRespec()) {
            player.sendSystemMessage(Component.literal("You need at least " + config.getMinLevelToRespec() +
                    " points in this category to respec!").withStyle(ChatFormatting.RED));
            return;
        }

        Optional<Experience> experienceOpt = category.getExperience();
        if (experienceOpt.isEmpty()) {
            player.sendSystemMessage(Component.literal("This skill category has no experience system attached.").withStyle(ChatFormatting.RED));
            return;
        }
        Experience experience = experienceOpt.get();

        int currentXp = experience.getTotal(player);
        category.erase(player);

        int newXP = (int) (currentXp * config.getXpReductionFactor());
        if (newXP > 0) {
            experience.addTotal(player, newXP);
            LOGGER.info("Restored {} XP to {} for category {}", newXP, player.getName().getString(), categoryId);
        }

        player.getItemInHand(hand).shrink(1);

        if (level instanceof ServerLevel serverLevel) {
            LightningBolt lightning = new LightningBolt(EntityType.LIGHTNING_BOLT, serverLevel);
            lightning.setVisualOnly(true);
            lightning.setPos(pos.getCenter().add(0, 0.5, 0));
            serverLevel.addFreshEntity(lightning);
        }
        level.playSound(null, pos, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.BLOCKS, 1.0f, 1.0f);

        player.sendSystemMessage(Component.literal("Your " + categoryId.getPath() + " skills have been reset!").withStyle(ChatFormatting.GOLD));
        player.sendSystemMessage(Component.literal("You are now level " + experience.getLevel(player)).withStyle(ChatFormatting.AQUA));
        player.sendSystemMessage(Component.literal("A surge of power washes over you...").withStyle(ChatFormatting.GRAY));
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("respecskill")
                        .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                        .then(Commands.literal("reload").executes(context -> {
                            config.load();
                            context.getSource().sendSuccess(
                                    () -> Component.literal("Respec Skill config reloaded (" + config.getAltars().size() + " altars).")
                                            .withStyle(ChatFormatting.GREEN),
                                    true);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("give").executes(context -> {
                            ServerPlayer p = context.getSource().getPlayerOrException();
                            p.getInventory().placeItemBackInInventory(createRespecScroll());
                            context.getSource().sendSuccess(() -> Component.literal("Gave you a Respec Scroll.").withStyle(ChatFormatting.GREEN), false);
                            return Command.SINGLE_SUCCESS;
                        }))));
    }
}
