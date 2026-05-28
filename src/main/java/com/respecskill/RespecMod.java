package com.respecskill;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.puffish.skillsmod.api.Category;
import net.puffish.skillsmod.api.Experience;
import net.puffish.skillsmod.api.SkillsAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class RespecMod implements ModInitializer {

    public static final String MOD_ID = "respec-skill";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static RespecConfig config;
    private static final Map<UUID, Long> lastRespecAt = new HashMap<>();

    @Override
    public void onInitialize() {
        LOGGER.info("Respec Skill Mod Initialized!");
        config = new RespecConfig();
        config.load();
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                LOGGER.info("Respec Skill ready ({} Puffish categories discovered)", SkillsAPI.streamCategories().count()));
        registerCommands();
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("respec")
                        .executes(ctx -> showMenu(ctx.getSource()))
                        .then(Commands.literal("reload")
                                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                                .executes(ctx -> {
                                    config.load();
                                    ctx.getSource().sendSuccess(() -> Component.literal("Respec config reloaded.")
                                            .withStyle(ChatFormatting.GREEN), true);
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .then(Commands.literal("prestige")
                                .executes(ctx -> showPrestigeMenu(ctx.getSource()))
                                .then(Commands.argument("from_category", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            Identifier id = Identifier.tryParse(StringArgumentType.getString(ctx, "from_category"));
                                            if (id == null) {
                                                ctx.getSource().sendFailure(Component.literal("Invalid category id."));
                                                return 0;
                                            }
                                            return doPrestige(ctx.getSource(), id);
                                        })))
                        .then(Commands.argument("category", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    Identifier id = Identifier.tryParse(StringArgumentType.getString(ctx, "category"));
                                    if (id == null) {
                                        ctx.getSource().sendFailure(Component.literal("Invalid category id."));
                                        return 0;
                                    }
                                    return doRespec(ctx.getSource(), id);
                                }))));
    }

    private static int showMenu(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            List<Category> categories = SkillsAPI.streamCategories().toList();
            if (categories.isEmpty()) {
                source.sendFailure(Component.literal("No Puffish skill categories are loaded on this server."));
                return 0;
            }
            player.sendSystemMessage(Component.literal("=== Respec ===").withStyle(ChatFormatting.GOLD));
            for (Category category : categories) {
                Identifier id = category.getId();
                RespecConfig.Cost cost = config.costFor(id);
                String costName = new ItemStack(cost.item()).getHoverName().getString();
                String idStr = id.toString();

                MutableComponent line = Component.literal(" • ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(id.getPath()).withStyle(ChatFormatting.AQUA))
                        .append(Component.literal("  (cost: " + cost.count() + " × " + costName + ")  ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal("[Reset]").withStyle(s -> s
                                .withColor(ChatFormatting.GREEN)
                                .withBold(true)
                                .withClickEvent(new ClickEvent.RunCommand("/respec " + idStr))
                                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to respec " + idStr)))));
                player.sendSystemMessage(line);
            }
            if (!config.getPrestigeMappings().isEmpty()) {
                player.sendSystemMessage(Component.literal("Prestige paths available — see ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal("[/respec prestige]").withStyle(s -> s
                                .withColor(ChatFormatting.LIGHT_PURPLE).withBold(true)
                                .withClickEvent(new ClickEvent.RunCommand("/respec prestige")))));
            }
            return Command.SINGLE_SUCCESS;
        } catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal("Run /respec as a player."));
            return 0;
        }
    }

    private static int doRespec(CommandSourceStack source, Identifier categoryId) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal("Run /respec as a player."));
            return 0;
        }

        Optional<Category> categoryOpt = SkillsAPI.getCategory(categoryId);
        if (categoryOpt.isEmpty()) {
            player.sendSystemMessage(Component.literal("Category '" + categoryId + "' is not loaded on this server.").withStyle(ChatFormatting.RED));
            return 0;
        }
        Category category = categoryOpt.get();
        RespecConfig.Cost cost = config.costFor(categoryId);

        long now = player.level().getGameTime();
        long cooldownTicks = config.getCooldownSeconds() * 20L;
        Long last = lastRespecAt.get(player.getUUID());
        if (cooldownTicks > 0 && last != null && now - last < cooldownTicks) {
            long remaining = (cooldownTicks - (now - last)) / 20;
            player.sendSystemMessage(Component.literal("You must wait " + remaining + "s before respecing again.").withStyle(ChatFormatting.RED));
            return 0;
        }

        if (category.getPointsTotal(player) < config.getMinLevelToRespec()) {
            player.sendSystemMessage(Component.literal("You need at least " + config.getMinLevelToRespec() +
                    " points in this category to respec.").withStyle(ChatFormatting.RED));
            return 0;
        }

        Optional<Experience> experienceOpt = category.getExperience();
        if (experienceOpt.isEmpty()) {
            player.sendSystemMessage(Component.literal("This category has no experience system attached.").withStyle(ChatFormatting.RED));
            return 0;
        }
        Experience experience = experienceOpt.get();

        if (!chargeCost(player, cost)) {
            String costName = new ItemStack(cost.item()).getHoverName().getString();
            player.sendSystemMessage(Component.literal("You need " + cost.count() + " × " + costName + " to respec.").withStyle(ChatFormatting.RED));
            return 0;
        }

        int currentXp = experience.getTotal(player);
        category.erase(player);
        int newXP = (int) (currentXp * config.getXpReductionFactor());
        if (newXP > 0) experience.addTotal(player, newXP);

        lastRespecAt.put(player.getUUID(), now);

        player.level().playSound(null, player.blockPosition(),
                SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0f, 0.6f);

        player.sendSystemMessage(Component.literal("Your " + categoryId.getPath() + " skills have been reset.").withStyle(ChatFormatting.GOLD));
        player.sendSystemMessage(Component.literal("You are now level " + experience.getLevel(player) + ".").withStyle(ChatFormatting.AQUA));
        return Command.SINGLE_SUCCESS;
    }

    private static int showPrestigeMenu(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            List<RespecConfig.PrestigeMapping> paths = config.getPrestigeMappings();
            if (paths.isEmpty()) {
                source.sendFailure(Component.literal("No prestige paths are configured."));
                return 0;
            }
            player.sendSystemMessage(Component.literal("=== Prestige ===").withStyle(ChatFormatting.LIGHT_PURPLE));
            for (RespecConfig.PrestigeMapping pm : paths) {
                String costName = new ItemStack(pm.cost().item()).getHoverName().getString();
                int currentPoints = SkillsAPI.getCategory(pm.from())
                        .map(c -> c.getPointsTotal(player)).orElse(0);
                boolean eligible = currentPoints >= pm.minPoints();
                ChatFormatting nameColor = eligible ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY;
                ChatFormatting buttonColor = eligible ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.DARK_GRAY;

                MutableComponent line = Component.literal(" • ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(pm.from().getPath()).withStyle(nameColor))
                        .append(Component.literal(" → ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(pm.to().getPath()).withStyle(nameColor))
                        .append(Component.literal("  (req: " + pm.minPoints() + " pts; have " + currentPoints +
                                ";  cost: " + pm.cost().count() + " × " + costName + ")  ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal("[Ascend]").withStyle(s -> s
                                .withColor(buttonColor).withBold(true)
                                .withClickEvent(new ClickEvent.RunCommand("/respec prestige " + pm.from()))
                                .withHoverEvent(new HoverEvent.ShowText(Component.literal(
                                        eligible ? "Click to ascend " + pm.from() : "Not enough points yet")))));
                player.sendSystemMessage(line);
            }
            return Command.SINGLE_SUCCESS;
        } catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal("Run /respec prestige as a player."));
            return 0;
        }
    }

    private static int doPrestige(CommandSourceStack source, Identifier fromCategoryId) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal("Run /respec prestige as a player."));
            return 0;
        }

        RespecConfig.PrestigeMapping pm = config.prestigeFrom(fromCategoryId);
        if (pm == null) {
            player.sendSystemMessage(Component.literal("No prestige path configured from '" + fromCategoryId + "'.").withStyle(ChatFormatting.RED));
            return 0;
        }

        Optional<Category> fromOpt = SkillsAPI.getCategory(pm.from());
        Optional<Category> toOpt = SkillsAPI.getCategory(pm.to());
        if (fromOpt.isEmpty() || toOpt.isEmpty()) {
            player.sendSystemMessage(Component.literal("Prestige source or target category is not loaded on this server.").withStyle(ChatFormatting.RED));
            return 0;
        }
        Category fromCat = fromOpt.get();
        Category toCat = toOpt.get();

        if (fromCat.getPointsTotal(player) < pm.minPoints()) {
            player.sendSystemMessage(Component.literal("You need at least " + pm.minPoints() +
                    " points in " + pm.from().getPath() + " to prestige.").withStyle(ChatFormatting.RED));
            return 0;
        }

        Optional<Experience> fromXpOpt = fromCat.getExperience();
        Optional<Experience> toXpOpt = toCat.getExperience();
        if (fromXpOpt.isEmpty() || toXpOpt.isEmpty()) {
            player.sendSystemMessage(Component.literal("Prestige requires both categories to have an XP system.").withStyle(ChatFormatting.RED));
            return 0;
        }

        if (!chargeCost(player, pm.cost())) {
            String costName = new ItemStack(pm.cost().item()).getHoverName().getString();
            player.sendSystemMessage(Component.literal("You need " + pm.cost().count() + " × " + costName + " to prestige.").withStyle(ChatFormatting.RED));
            return 0;
        }

        int currentXp = fromXpOpt.get().getTotal(player);
        fromCat.erase(player);
        int carry = (int) (currentXp * pm.xpCarryover());
        if (carry > 0) toXpOpt.get().addTotal(player, carry);

        player.level().playSound(null, player.blockPosition(),
                SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0f, 0.8f);

        player.sendSystemMessage(Component.literal("You have ascended: " + pm.from().getPath() + " → " + pm.to().getPath() + ".").withStyle(ChatFormatting.LIGHT_PURPLE));
        if (carry > 0) {
            player.sendSystemMessage(Component.literal("Carried over " + carry + " XP into your new path.").withStyle(ChatFormatting.AQUA));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static boolean chargeCost(ServerPlayer player, RespecConfig.Cost cost) {
        Inventory inv = player.getInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(cost.item())) total += s.getCount();
            if (total >= cost.count()) break;
        }
        if (total < cost.count()) return false;

        int remaining = cost.count();
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(cost.item())) continue;
            int take = Math.min(remaining, s.getCount());
            s.shrink(take);
            remaining -= take;
        }
        return true;
    }
}
