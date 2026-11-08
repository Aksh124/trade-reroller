package tradereroller;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.Optional;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.WorldCoordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class TradeRerollerClient implements ClientModInitializer {
	private static final KeyMapping OPEN_SETTINGS = new KeyMapping("key.tradereroller.settings", InputConstants.UNKNOWN.getValue(),
		KeyMapping.Category.register(Identifier.fromNamespaceAndPath("tradereroller", "keys")));
	/** Open the screen on the next tick, after the chat screen the command came from has closed. */
	private static boolean openSettingsNextTick;

	@Override
	public void onInitializeClient() {
		Settings.load();
		KeyMappingHelper.registerKeyMapping(OPEN_SETTINGS);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> registerCommands(dispatcher));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			Reroller.tick(mc);
			while (OPEN_SETTINGS.consumeClick()) {
				openSettingsNextTick = true;
			}
			if (openSettingsNextTick && mc.player != null) {
				openSettingsNextTick = false;
				mc.gui.setScreen(new SettingsScreen());
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> Reroller.stop(client)));
	}

	private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(literal("reroll")
			.executes(context -> status(context.getSource()))
			.then(literal("menu").executes(context -> {
				openSettingsNextTick = true;
				return Command.SINGLE_SUCCESS;
			}))
			.then(literal("stop").executes(context -> {
				boolean was = Reroller.stop(Minecraft.getInstance());
				context.getSource().sendFeedback(Component.literal(was ? "Stopped rerolling after " + Reroller.rolls() + " rolls."
					: "Not rerolling."));
				return Command.SINGLE_SUCCESS;
			}))
			.then(literal("start")
				.executes(context -> report(context.getSource(), startFromSettings(Minecraft.getInstance())))
				.then(argument("lectern", BlockPosArgument.blockPos())
					.then(argument("enchantment", IdentifierArgument.id())
						.suggests((context, builder) -> {
							Minecraft mc = Minecraft.getInstance();
							return mc.level == null ? builder.buildFuture()
								: SharedSuggestionProvider.suggestResource(enchantments(mc).keySet(), builder);
						})
						.executes(context -> start(context, 0, 0))
						.then(argument("level", IntegerArgumentType.integer(1, 255))
							.executes(context -> start(context, IntegerArgumentType.getInteger(context, "level"), 0))
							.then(argument("max price", IntegerArgumentType.integer(1, 64))
								.executes(context -> start(context, IntegerArgumentType.getInteger(context, "level"),
									IntegerArgumentType.getInteger(context, "max price")))))))));
	}

	private static int start(CommandContext<FabricClientCommandSource> context, int level, int maxPrice) {
		Minecraft mc = Minecraft.getInstance();
		BlockPos pos = resolve(context.getArgument("lectern", Coordinates.class), mc.player);
		if (pos == null) {
			context.getSource().sendError(Component.literal("Use world coordinates (x y z or ~ ~ ~), not ^ ^ ^."));
			return 0;
		}
		return report(context.getSource(), start(mc, pos, context.getArgument("enchantment", Identifier.class).toString(), level, maxPrice));
	}

	private static int report(FabricClientCommandSource source, String error) {
		if (error != null) {
			source.sendError(Component.literal(error));
			return 0;
		}
		return Command.SINGLE_SUCCESS;
	}

	/** Starts with what's saved, for /reroll start on its own. */
	private static String startFromSettings(Minecraft mc) {
		if (Settings.lectern == null) {
			return "No lectern saved yet. Use /reroll menu or /reroll start <lectern x y z> <enchantment>.";
		}
		return start(mc, Settings.lectern, Settings.enchantment, Settings.minLevel, Settings.maxPrice);
	}

	/**
	 * Starts rerolling and saves the choices. A level of 0 means the enchantment's highest level, a
	 * price of 0 means any price. Returns an error to show, or null.
	 */
	public static String start(Minecraft mc, BlockPos pos, String enchantmentId, int level, int maxPrice) {
		if (mc.level == null || mc.player == null) {
			return "Join a world first.";
		}
		pos = lecternAt(mc, pos);
		Identifier id = Identifier.tryParse(enchantmentId);
		Optional<Holder.Reference<Enchantment>> enchantment = id == null ? Optional.empty()
			: enchantments(mc).get(ResourceKey.create(Registries.ENCHANTMENT, id));
		if (enchantment.isEmpty()) {
			return "Unknown enchantment: " + enchantmentId;
		}
		int maxLevel = enchantment.get().value().getMaxLevel();
		if (level > maxLevel) {
			return enchantment.get().value().description().getString() + " only goes up to level " + maxLevel + ".";
		}

		Settings.lectern = pos;
		Settings.enchantment = id.toString();
		Settings.minLevel = level;
		Settings.maxPrice = maxPrice;
		Settings.save();

		Reroller.Target target = new Reroller.Target(enchantment.get(), level == 0 ? maxLevel : level,
			maxPrice == 0 ? Integer.MAX_VALUE : maxPrice);
		String error = Reroller.start(mc, pos, target);
		if (error == null) {
			Reroller.message(Component.literal("Rerolling the lectern at " + pos.toShortString() + " for ")
				.append(target.describe()).append(". /reroll stop to stop."));
		}
		return error;
	}

	/** Coordinates of the block a lectern stands on mean the lectern. */
	static BlockPos lecternAt(Minecraft mc, BlockPos pos) {
		if (!mc.level.getBlockState(pos).is(Blocks.LECTERN) && mc.level.getBlockState(pos.above()).is(Blocks.LECTERN)) {
			return pos.above();
		}
		return pos;
	}

	/** The lectern the player is looking at, or null. */
	static BlockPos lookedAtLectern(Minecraft mc) {
		if (mc.level == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			return null;
		}
		BlockPos pos = lecternAt(mc, hit.getBlockPos());
		return mc.level.getBlockState(pos).is(Blocks.LECTERN) ? pos : null;
	}

	private static int status(FabricClientCommandSource source) {
		source.sendFeedback(Component.literal(statusText()));
		return Command.SINGLE_SUCCESS;
	}

	static String statusText() {
		Reroller.Step step = Reroller.step();
		if (step == null) {
			String last = Reroller.lastResult();
			return "Not rerolling." + (last == null ? "" : " Last run: " + last);
		}
		String doing = switch (step) {
			case WAIT_FOR_LIBRARIAN -> "waiting for a villager to take the lectern";
			case OPEN_TRADES -> "checking the trades";
			case BREAK -> "breaking the lectern";
			case WAIT_FOR_JOB_LOSS -> "waiting for the villager to lose its job";
			case PLACE, CHECK_PLACED -> Reroller.waitingForLectern()
				? "paused until you have a lectern (the dropped one is out of reach)" : "putting the lectern back";
		};
		return "Rerolling for " + Reroller.target().describe().getString() + ": " + doing + ", " + Reroller.rolls() + " rolls so far.";
	}

	private static BlockPos resolve(Coordinates coordinates, LocalPlayer player) {
		if (!(coordinates instanceof WorldCoordinates world)) {
			return null;
		}
		return BlockPos.containing(world.x().get(player.getX()), world.y().get(player.getY()), world.z().get(player.getZ()));
	}

	static Registry<Enchantment> enchantments(Minecraft mc) {
		return mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
	}
}
