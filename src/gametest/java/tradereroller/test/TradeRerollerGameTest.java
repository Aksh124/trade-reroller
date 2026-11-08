package tradereroller.test;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import tradereroller.Reroller;
import tradereroller.Settings;
import tradereroller.SettingsScreen;

/**
 * A real survival reroll: a caged villager next to a lectern, the axe in the main inventory
 * (not the hotbar), and no spare lectern, so the dropped one has to be picked up each time.
 */
public class TradeRerollerGameTest implements FabricClientGameTest {
	private static final BlockPos LECTERN = new BlockPos(2, 100, 0);

	@Override
	public void runTest(ClientGameTestContext context) {
		fromModMenuOnTheTitleScreen(context);
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TradeRerollerGameTest.world = world;
			world.getConnection().waitForChunksRender();
			for (String command : new String[] {
				"difficulty peaceful", "time set noon", "gamemode survival @a",
				"fill -6 99 -6 6 99 6 stone", "fill -6 100 -6 6 104 6 air", "kill @e[type=!player]",
				"setblock 2 100 0 lectern",
				// A glass cell south of the lectern holds the villager next to it.
				"setblock 1 100 1 glass", "setblock 1 101 1 glass", "setblock 3 100 1 glass", "setblock 3 101 1 glass",
				"setblock 2 100 2 glass", "setblock 2 101 2 glass", "setblock 2 102 1 glass", "setblock 2 101 0 glass",
				"summon villager 2.5 100 1.5",
				"tp @a 2.5 100 -0.5 0 30",
				"clear @a", "item replace entity @a inventory.5 with iron_axe",
			}) {
				world.getServer().runCommand(command);
			}
			context.waitTicks(20);

			rerollUntilBooksShowUp(context);
			findTheBook(context, world);
			settingsScreen(context);
			refuseWithoutAnAxe(context, world);
			refuseATradedVillager(context, world);
			log("handed over a lectern " + pauses + " times when a dropped one landed out of reach");
		}
	}

	/** Mod Menu's config button, before any world: settings editable, no enchantment list, no Start. */
	private static void fromModMenuOnTheTitleScreen(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			var ours = net.fabricmc.loader.api.FabricLoader.getInstance()
				.getEntrypointContainers("modmenu", com.terraformersmc.modmenu.api.ModMenuApi.class).stream()
				.filter(entry -> entry.getProvider().getMetadata().getId().equals("tradereroller")).toList();
			check(ours.size() == 1, "Trade Reroller's Mod Menu entrypoint not found");
			mc.gui.setScreen(ours.getFirst().getEntrypoint().getModConfigScreenFactory().create(mc.gui.screen()));
		});
		context.waitTicks(2);
		check(context.computeOnClient(mc -> screen() != null && screen().shownEnchantments().isEmpty()
			&& !screen().startStopButton().active), "title-screen settings not in their no-world state");
		context.takeScreenshot("tradereroller-modmenu");
		context.runOnClient(mc -> screen().onClose());
		context.waitTicks(2);
		check(context.computeOnClient(mc -> mc.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen),
			"Done didn't go back to the screen it came from: " + context.computeOnClient(mc -> String.valueOf(mc.gui.screen())));
		log("opens from Mod Menu on the title screen and goes back to it");
	}

	private static TestSingleplayerContext world;
	private static int pauses;

	/** Coordinates of the block under the lectern, and a target that can never match. */
	private static void rerollUntilBooksShowUp(ClientGameTestContext context) {
		context.runOnClient(mc -> mc.player.connection.sendCommand("reroll start 2 99 0 minecraft:mending 1 1"));
		waitUntil(context, 5, () -> Reroller.step() != null, () -> "didn't start: " + Reroller.lastResult());
		check(Reroller.lectern().equals(LECTERN), "the block under the lectern wasn't taken as the lectern: " + Reroller.lectern());
		log("started, waiting for rolls");

		// At least three rolls, then stop just after one that offered a book, before the lectern breaks.
		waitUntil(context, 900, () -> {
			if (Reroller.step() == null) {
				throw new AssertionError("stopped: " + Reroller.lastResult());
			}
			return Reroller.rolls() >= 3 && Reroller.lastRoll().enchantment() != null && Reroller.step() == Reroller.Step.BREAK;
		}, () -> "only " + Reroller.rolls() + " rolls, step " + Reroller.step());
		context.runOnClient(mc -> mc.player.connection.sendCommand("reroll stop"));
		context.waitTicks(5);
		check(Reroller.step() == null, "didn't stop");

		int axeDamage = context.computeOnClient(mc -> {
			for (int slot = 0; slot < 9; slot++) {
				if (mc.player.getInventory().getItem(slot).is(Items.IRON_AXE)) {
					return mc.player.getInventory().getItem(slot).getDamageValue();
				}
			}
			return -1;
		});
		check(axeDamage >= Reroller.rolls() - 1, "the axe wasn't moved to the hotbar and used for each break: damage " + axeDamage);
		check(context.computeOnClient(mc -> mc.level.getBlockState(LECTERN).is(Blocks.LECTERN)), "lectern missing after stopping");
		log(Reroller.rolls() + " rolls, last " + Reroller.lastRoll().describe().getString() + ", axe damage " + axeDamage);
	}

	/** The book on offer right now, asked for exactly: found on the first look, trade screen left open. */
	private static void findTheBook(ClientGameTestContext context, TestSingleplayerContext world) {
		Reroller.Roll offered = Reroller.lastRoll();
		String id = offered.enchantment().unwrapKey().orElseThrow().identifier().toString();
		context.runOnClient(mc -> mc.player.connection.sendCommand("reroll start 2 100 0 " + id + " " + offered.level() + " " + offered.price()));
		waitUntil(context, 30, () -> Reroller.step() == null, () -> "still running: " + Reroller.step());
		check(Reroller.lastResult().startsWith("Found") && Reroller.rolls() == 1, "expected a find on roll 1: " + Reroller.lastResult());
		check(context.computeOnClient(mc -> mc.gui.screen() instanceof MerchantScreen), "trade screen not left open");
		log(Reroller.lastResult());
		context.takeScreenshot("tradereroller-found");
		context.runOnClient(mc -> mc.player.closeContainer());
		context.waitTicks(5);
	}

	/** A villager that's been traded with keeps its trades, so the lectern must not be broken. */
	private static void refuseATradedVillager(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			for (Villager villager : server.overworld().getEntitiesOfClass(Villager.class, new AABB(LECTERN).inflate(8))) {
				villager.setVillagerXp(5);
			}
		});
		context.runOnClient(mc -> mc.player.connection.sendCommand("reroll start 2 100 0 minecraft:mending"));
		waitUntil(context, 30, () -> Reroller.step() == null, () -> "still running: " + Reroller.step());
		check(Reroller.lastResult().contains("has been traded with"), "traded villager not refused: " + Reroller.lastResult());
		check(context.computeOnClient(mc -> mc.level.getBlockState(LECTERN).is(Blocks.LECTERN)), "lectern broken for a traded villager");
		check(context.computeOnClient(mc -> mc.gui.screen() == null), "trade screen left open");
		log("traded villager refused: " + Reroller.lastResult());
	}

	/** The settings screen: looked-at lectern, search, a bad price, then Start and Stop. */
	private static void settingsScreen(ClientGameTestContext context) {
		// Look down at the lectern, then open the screen with the command.
		context.runOnClient(mc -> {
			mc.player.setYRot(0);
			mc.player.setXRot(70);
		});
		context.waitTicks(3);
		context.runOnClient(mc -> mc.player.connection.sendCommand("reroll menu"));
		waitUntil(context, 5, () -> screen() != null, () -> "/reroll menu didn't open the screen");

		List<String> found = context.computeOnClient(mc -> {
			screen().searchBox().setValue("mend");
			return screen().shownEnchantments();
		});
		check(found.equals(List.of("minecraft:mending")), "search for 'mend' showed " + found);
		int all = context.computeOnClient(mc -> {
			screen().searchBox().setValue("");
			return screen().shownEnchantments().size();
		});
		check(all > 30, "only " + all + " enchantments listed");
		context.runOnClient(mc -> {
			screen().searchBox().setValue("mending");
			screen().pick(0);
		});
		String levelText = context.computeOnClient(mc -> screen().levelButton().getMessage().getString());
		check(levelText.equals("Level: highest (I)"), "level button says " + levelText);

		// A price over 64 is refused with a message, and nothing starts.
		context.runOnClient(mc -> {
			screen().maxPriceBox().setValue("99");
			screen().startStopButton().onPress(null);
		});
		check(context.computeOnClient(mc -> Reroller.step() == null && screen() != null && screen().error().startsWith("Max emeralds")),
			"bad price not refused: " + context.computeOnClient(mc -> screen() == null ? "screen closed" : screen().error()));
		context.runOnClient(mc -> screen().maxPriceBox().setValue("1"));
		context.waitTicks(2);
		context.takeScreenshot("tradereroller-settings");

		// Start: the screen closes, the looked-at lectern was used, and rolls happen.
		int rollsBefore = Reroller.rolls();
		context.runOnClient(mc -> screen().startStopButton().onPress(null));
		context.waitTicks(2);
		check(context.computeOnClient(mc -> screen() == null && Reroller.step() != null), "Start didn't start: " + Reroller.lastResult());
		check(Reroller.lectern().equals(LECTERN), "screen didn't use the looked-at lectern: " + Reroller.lectern());
		check(Settings.maxPrice == 1 && Settings.enchantment.equals("minecraft:mending"), "settings not saved");
		check(context.computeOnClient(mc -> {
			try {
				String file = java.nio.file.Files.readString(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
					.resolve("tradereroller.properties"));
				return file.contains("maxPrice=1") && file.contains("lectern=2,100,0");
			} catch (java.io.IOException e) {
				return false;
			}
		}), "settings file not written");
		waitUntil(context, 120, () -> {
			if (Reroller.step() == null) {
				throw new AssertionError("stopped: " + Reroller.lastResult());
			}
			return Reroller.step() == Reroller.Step.BREAK && Reroller.rolls() >= rollsBefore + 2;
		}, () -> "no rolls from the screen, step " + Reroller.step() + ": " + Reroller.lastResult());

		// Stop from the screen, before the lectern breaks.
		context.runOnClient(mc -> {
			mc.gui.setScreen(new SettingsScreen());
			screen().startStopButton().onPress(null);
		});
		context.waitTicks(2);
		check(Reroller.step() == null, "Stop didn't stop");
		check(context.computeOnClient(mc -> screen().startStopButton().getMessage().getString()).equals("Start rerolling"),
			"button didn't switch back to Start");
		context.runOnClient(mc -> screen().onClose());
		context.waitTicks(5);
		check(context.computeOnClient(mc -> mc.level.getBlockState(LECTERN).is(Blocks.LECTERN)), "lectern missing after Stop");
		log("settings screen: search, bad price, start (" + (Reroller.rolls()) + " rolls) and stop all work");
	}

	private static SettingsScreen screen() {
		return net.minecraft.client.Minecraft.getInstance().gui.screen() instanceof SettingsScreen screen ? screen : null;
	}

	/** No axe: refused before anything happens. An axe about to break doesn't count either. */
	private static void refuseWithoutAnAxe(ClientGameTestContext context, TestSingleplayerContext world) {
		String before = Reroller.lastResult();
		world.getServer().runCommand("clear @a iron_axe");
		world.getServer().runCommand("item replace entity @a inventory.5 with iron_axe[damage=245]");
		context.waitTicks(5);
		check(context.computeOnClient(mc -> mc.player.getInventory().countItem(Items.IRON_AXE)) == 1, "axe not swapped for a worn one");
		context.runOnClient(mc -> mc.player.connection.sendCommand("reroll start 2 100 0 minecraft:mending"));
		context.waitTicks(5);
		check(Reroller.step() == null && Reroller.lastResult() == before, "started without a usable axe: " + Reroller.lastResult());
		check(context.computeOnClient(mc -> mc.level.getBlockState(LECTERN).is(Blocks.LECTERN)), "lectern broken without an axe");
		log("worn-out axe refused");
		world.getServer().runCommand("clear @a iron_axe");
		world.getServer().runCommand("item replace entity @a inventory.5 with iron_axe");
		context.waitTicks(5);
	}

	private static void waitUntil(ClientGameTestContext context, int seconds, BooleanSupplier done, Supplier<String> failure) {
		for (int tick = 0; tick < seconds * 20; tick++) {
			// Paused for a lectern that landed out of reach: hand one over, as a player would, and it carries on.
			if (context.computeOnClient(mc -> Reroller.waitingForLectern() && mc.player.getInventory().countItem(Items.LECTERN) == 0)) {
				check(context.computeOnClient(mc -> Reroller.step() != null), "gave up instead of pausing");
				world.getServer().runCommand("give @a lectern");
				pauses++;
				context.waitTicks(5);
			}
			if (context.computeOnClient(mc -> done.getAsBoolean())) {
				return;
			}
			context.waitTick();
		}
		throw new AssertionError(failure.get());
	}

	private static void check(boolean condition, String failure) {
		if (!condition) {
			throw new AssertionError(failure);
		}
	}

	private static void log(String text) {
		System.out.println("[tradereroller-gametest] " + text);
	}
}
