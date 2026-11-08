package tradereroller;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The reroll loop, run one step per client tick:
 * check the librarian's trades, break the lectern with an axe, wait for the villager to lose its
 * job, put the lectern back, wait for a villager to take it, and check again.
 */
public final class Reroller {
	/** The book being looked for. */
	public record Target(Holder<Enchantment> enchantment, int minLevel, int maxPrice) {
		public Component describe() {
			MutableComponent text = Enchantment.getFullname(enchantment, minLevel).copy();
			if (minLevel < enchantment.value().getMaxLevel()) {
				text.append(" or higher");
			}
			return maxPrice < Integer.MAX_VALUE ? text.append(" for " + maxPrice + " emeralds or less") : text;
		}
	}

	/** One look at the trades: the enchanted book on offer, if there is one. */
	public record Roll(int number, Holder<Enchantment> enchantment, int level, int price) {
		public Component describe() {
			return enchantment == null ? Component.literal("no enchanted book")
				: Enchantment.getFullname(enchantment, level).copy().append(" for " + price + " emeralds");
		}
	}

	public enum Step { WAIT_FOR_LIBRARIAN, OPEN_TRADES, BREAK, WAIT_FOR_JOB_LOSS, PLACE, CHECK_PLACED }

	private static final double SEARCH_RADIUS = 16;
	private static final int OPEN_TRADES_TIMEOUT = 20 * 30;
	private static final int BREAK_TIMEOUT = 20 * 10;
	private static final int JOB_LOSS_TIMEOUT = 20 * 30;
	private static final int PICKUP_TIMEOUT = 20 * 5;
	private static final int NEW_LIBRARIAN_TIMEOUT = 20 * 120;
	private static final int PLACE_ATTEMPTS = 3;

	private static Step step;
	private static BlockPos lectern;
	private static Target target;
	private static int villagerId = -1;
	/** Librarians that already existed when the lectern went down; the new one is whoever isn't here. */
	private static Set<Integer> librariansBefore = Set.of();
	private static int stepTicks;
	private static int rolls;
	private static Roll lastRoll;
	private static int placeAttempts;
	private static boolean breaking;
	private static String lastResult;

	private Reroller() {}

	public static boolean isBreaking() {
		return breaking;
	}

	public static Step step() {
		return step;
	}

	public static int rolls() {
		return rolls;
	}

	public static Roll lastRoll() {
		return lastRoll;
	}

	/** How the last run ended, for status and tests. */
	public static String lastResult() {
		return lastResult;
	}

	public static BlockPos lectern() {
		return lectern;
	}

	public static Target target() {
		return target;
	}

	/** Starts rerolling; returns an error to show, or null. */
	static String start(Minecraft mc, BlockPos lecternPos, Target wanted) {
		LocalPlayer player = mc.player;
		if (!mc.level.getBlockState(lecternPos).is(Blocks.LECTERN)) {
			return "There's no lectern at " + lecternPos.toShortString() + ".";
		}
		if (!player.isWithinBlockInteractionRange(lecternPos, 0)) {
			return "The lectern is out of reach. Stand next to it and the librarian.";
		}
		if (findAxe(player) < 0) {
			return "You need an axe with more than " + Settings.axeUsesToKeep + " uses left in your inventory.";
		}
		stopQuietly(mc);
		lectern = lecternPos;
		target = wanted;
		rolls = 0;
		lastRoll = null;
		lastResult = null;
		Villager librarian = closestLibrarian(mc, Set.of());
		if (librarian != null) {
			villagerId = librarian.getId();
			go(Step.OPEN_TRADES);
		} else {
			villagerId = -1;
			librariansBefore = Set.of();
			go(Step.WAIT_FOR_LIBRARIAN);
			message(Component.literal("No librarian nearby yet. Waiting for a villager to take the lectern."));
		}
		return null;
	}

	/** Stops a run the user cancelled. Returns whether one was running. */
	static boolean stop(Minecraft mc) {
		boolean was = step != null;
		if (was) {
			lastResult = "Stopped after " + rolls + " rolls.";
		}
		stopQuietly(mc);
		return was;
	}

	private static void stopQuietly(Minecraft mc) {
		if (breaking && mc.gameMode != null) {
			mc.gameMode.stopDestroyBlock();
		}
		breaking = false;
		step = null;
	}

	static void tick(Minecraft mc) {
		if (step == null) {
			return;
		}
		if (mc.player == null || mc.level == null || mc.gameMode == null) {
			stopQuietly(mc);
			return;
		}
		stepTicks++;
		switch (step) {
			case WAIT_FOR_LIBRARIAN -> waitForLibrarian(mc);
			case OPEN_TRADES -> openTrades(mc);
			case BREAK -> breakLectern(mc);
			case WAIT_FOR_JOB_LOSS -> waitForJobLoss(mc);
			case PLACE -> placeLectern(mc);
			case CHECK_PLACED -> checkPlaced(mc);
		}
	}

	private static void waitForLibrarian(Minecraft mc) {
		Villager librarian = closestLibrarian(mc, librariansBefore);
		if (librarian != null) {
			villagerId = librarian.getId();
			go(Step.OPEN_TRADES);
		} else if (stepTicks > NEW_LIBRARIAN_TIMEOUT) {
			fail(mc, "No villager took the lectern in 2 minutes. Villagers don't take jobs while asleep, "
				+ "and they need to be able to walk to the lectern.");
		}
	}

	private static void openTrades(Minecraft mc) {
		LocalPlayer player = mc.player;
		Villager villager = villager(mc);
		if (villager == null) {
			fail(mc, "The librarian is gone.");
			return;
		}
		if (mc.gui.screen() instanceof MerchantScreen screen && !screen.getMenu().getOffers().isEmpty()) {
			checkOffers(mc, screen.getMenu());
			return;
		}
		if (mc.gui.screen() == null && (stepTicks == 1 || stepTicks % 40 == 0)) {
			if (!isLibrarian(villager)) {
				// Lost its job between checks; wait for it, or another villager, to take the lectern again.
				librariansBefore = librarianIds(mc);
				go(Step.WAIT_FOR_LIBRARIAN);
			} else if (player.isWithinEntityInteractionRange(villager, 0)) {
				Vec3 at = villager.getBoundingBox().getCenter();
				mc.gameMode.interact(player, villager, new EntityHitResult(villager, at), InteractionHand.MAIN_HAND);
			}
		}
		if (stepTicks > OPEN_TRADES_TIMEOUT) {
			fail(mc, "Couldn't open the librarian's trades. Make sure it can come within reach.");
		}
	}

	private static void checkOffers(Minecraft mc, MerchantMenu menu) {
		LocalPlayer player = mc.player;
		rolls++;
		lastRoll = bookOn(rolls, menu.getOffers());
		if (menu.getTraderXp() > 0 || menu.getTraderLevel() > 1) {
			player.closeContainer();
			fail(mc, "This librarian has been traded with, so it keeps its trades for good. Rerolling only works "
				+ "on a villager nobody has traded with.");
			return;
		}
		if (matches(lastRoll, target)) {
			// Leave the trade screen open so the trade can be locked in straight away.
			step = null;
			lastResult = "Found " + lastRoll.describe().getString() + " after " + rolls + (rolls == 1 ? " roll." : " rolls.");
			message(Component.literal("Found ").append(lastRoll.describe().copy().withStyle(ChatFormatting.GREEN))
				.append(" after " + rolls + (rolls == 1 ? " roll" : " rolls") + ". Trade for it once to lock it in."));
			if (Settings.foundSound) {
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1));
			}
			return;
		}
		player.closeContainer();
		if (Settings.rollMessages) {
			player.sendSystemMessage(Component.literal("Roll " + rolls + ": ").withStyle(ChatFormatting.GRAY)
				.append(lastRoll.describe().copy().withStyle(ChatFormatting.GRAY)));
		}
		go(Step.BREAK);
	}

	private static void breakLectern(Minecraft mc) {
		LocalPlayer player = mc.player;
		MultiPlayerGameMode gameMode = mc.gameMode;
		if (!mc.level.getBlockState(lectern).is(Blocks.LECTERN)) {
			breaking = false;
			go(Step.WAIT_FOR_JOB_LOSS);
			return;
		}
		if (stepTicks == 1) {
			if (!player.isWithinBlockInteractionRange(lectern, 0)) {
				fail(mc, "The lectern is out of reach.");
				return;
			}
			if (!select(player, gameMode, Reroller::isUsableAxe)) {
				fail(mc, "No axe with more than " + Settings.axeUsesToKeep + " uses left. Stopped so it doesn't break.");
				return;
			}
		}
		if (!isUsableAxe(player.getMainHandItem())) {
			fail(mc, "The axe is nearly broken. Stopped so it doesn't break.");
			return;
		}
		breaking = true;
		if (!gameMode.isDestroying()) {
			gameMode.startDestroyBlock(lectern, Direction.UP);
		} else {
			gameMode.continueDestroyBlock(lectern, Direction.UP);
		}
		player.swing(InteractionHand.MAIN_HAND);
		if (stepTicks > BREAK_TIMEOUT) {
			fail(mc, "Couldn't break the lectern.");
		}
	}

	private static void waitForJobLoss(Minecraft mc) {
		Villager villager = villager(mc);
		if (villager == null) {
			fail(mc, "The librarian is gone.");
		} else if (villager.getVillagerData().profession().is(VillagerProfession.NONE)) {
			placeAttempts = 0;
			go(Step.PLACE);
		} else if (!isLibrarian(villager)) {
			fail(mc, "The villager took a different job.");
		} else if (stepTicks > JOB_LOSS_TIMEOUT) {
			fail(mc, "The villager kept its job. It may have claimed another lectern nearby.");
		}
	}

	private static void placeLectern(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (mc.level.getBlockState(lectern).is(Blocks.LECTERN)) {
			librariansBefore = librarianIds(mc);
			go(Step.WAIT_FOR_LIBRARIAN);
			return;
		}
		if (!player.getInventory().contains(stack -> stack.is(Items.LECTERN))) {
			// Out of reach, usually in the villager's space. Wait, rather than give up, until there's one again.
			if (stepTicks == PICKUP_TIMEOUT) {
				ItemEntity dropped = droppedLectern(mc);
				message(Component.literal("Paused: the lectern "
					+ (dropped == null ? "didn't come back to you" : "landed out of reach at " + dropped.blockPosition().toShortString())
					+ ". Pick it up or get a spare lectern, and rerolling carries on by itself.").withStyle(ChatFormatting.YELLOW));
			}
			return;
		}
		if (!mc.level.getBlockState(lectern).canBeReplaced()) {
			fail(mc, "Something is in the lectern's spot.");
			return;
		}
		if (!player.isWithinBlockInteractionRange(lectern, 0)) {
			fail(mc, "The lectern's spot is out of reach.");
			return;
		}
		select(player, mc.gameMode, stack -> stack.is(Items.LECTERN));
		librariansBefore = librarianIds(mc);
		BlockPos below = lectern.below();
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(below).add(0, 0.5, 0), Direction.UP, below, false);
		mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
		player.swing(InteractionHand.MAIN_HAND);
		placeAttempts++;
		go(Step.CHECK_PLACED);
	}

	private static void checkPlaced(Minecraft mc) {
		if (mc.level.getBlockState(lectern).is(Blocks.LECTERN)) {
			go(Step.WAIT_FOR_LIBRARIAN);
		} else if (stepTicks > 20) {
			if (placeAttempts >= PLACE_ATTEMPTS) {
				fail(mc, "Couldn't put the lectern back. Is the block under it still there?");
			} else {
				go(Step.PLACE);
			}
		}
	}

	// Trades

	static Roll bookOn(int number, MerchantOffers offers) {
		for (MerchantOffer offer : offers) {
			ItemStack result = offer.getResult();
			if (!result.is(Items.ENCHANTED_BOOK)) {
				continue;
			}
			ItemEnchantments stored = result.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
			for (Object2IntMap.Entry<Holder<Enchantment>> entry : stored.entrySet()) {
				ItemStack cost = offer.getCostA();
				int price = cost.is(Items.EMERALD) ? cost.getCount() : 0;
				return new Roll(number, entry.getKey(), entry.getIntValue(), price);
			}
		}
		return new Roll(number, null, 0, 0);
	}

	static boolean matches(Roll roll, Target wanted) {
		return roll.enchantment() != null && roll.enchantment().is(wanted.enchantment())
			&& roll.level() >= wanted.minLevel() && roll.price() <= wanted.maxPrice();
	}

	private static ItemEntity droppedLectern(Minecraft mc) {
		for (ItemEntity item : mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(lectern).inflate(4),
			item -> item.getItem().is(Items.LECTERN))) {
			return item;
		}
		return null;
	}

	/** Paused waiting for a lectern, for status. */
	public static boolean waitingForLectern() {
		return step == Step.PLACE && stepTicks >= PICKUP_TIMEOUT;
	}

	// Villagers

	private static Villager villager(Minecraft mc) {
		Entity entity = mc.level.getEntity(villagerId);
		return entity instanceof Villager villager && villager.isAlive() ? villager : null;
	}

	private static boolean isLibrarian(Villager villager) {
		return villager.getVillagerData().profession().is(VillagerProfession.LIBRARIAN);
	}

	private static Iterable<Villager> villagersNearLectern(Minecraft mc) {
		return mc.level.getEntitiesOfClass(Villager.class, new AABB(lectern).inflate(SEARCH_RADIUS),
			villager -> villager.isAlive() && !villager.isBaby());
	}

	private static Set<Integer> librarianIds(Minecraft mc) {
		Set<Integer> ids = new HashSet<>();
		for (Villager villager : villagersNearLectern(mc)) {
			if (isLibrarian(villager)) {
				ids.add(villager.getId());
			}
		}
		return ids;
	}

	/** The librarian closest to the lectern, skipping the given ones. */
	private static Villager closestLibrarian(Minecraft mc, Set<Integer> skip) {
		Villager best = null;
		double bestDistance = Double.MAX_VALUE;
		Vec3 center = Vec3.atCenterOf(lectern);
		for (Villager villager : villagersNearLectern(mc)) {
			double distance = villager.distanceToSqr(center);
			if (isLibrarian(villager) && !skip.contains(villager.getId()) && distance < bestDistance) {
				best = villager;
				bestDistance = distance;
			}
		}
		return best;
	}

	// Inventory

	static boolean isUsableAxe(ItemStack stack) {
		return stack.is(ItemTags.AXES) && (!stack.isDamageableItem() || stack.getMaxDamage() - stack.getDamageValue() > Settings.axeUsesToKeep);
	}

	private static int findAxe(LocalPlayer player) {
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < 36; slot++) {
			if (isUsableAxe(inventory.getItem(slot))) {
				return slot;
			}
		}
		return -1;
	}

	/**
	 * Puts a matching item in the main hand: selects it if it's on the hotbar, otherwise swaps it
	 * into the selected hotbar slot.
	 */
	private static boolean select(LocalPlayer player, MultiPlayerGameMode gameMode, Predicate<ItemStack> wanted) {
		Inventory inventory = player.getInventory();
		if (wanted.test(inventory.getSelectedItem())) {
			return true;
		}
		for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
			if (wanted.test(inventory.getItem(slot))) {
				inventory.setSelectedSlot(slot);
				return true;
			}
		}
		for (int slot = Inventory.getSelectionSize(); slot < 36; slot++) {
			if (wanted.test(inventory.getItem(slot))) {
				// Inventory slots 9-35 have the same numbers in the player's inventory menu.
				gameMode.handleContainerInput(player.inventoryMenu.containerId, slot, inventory.getSelectedSlot(), ContainerInput.SWAP, player);
				return true;
			}
		}
		return false;
	}

	// Helpers

	private static void go(Step next) {
		step = next;
		stepTicks = 0;
	}

	private static void fail(Minecraft mc, String why) {
		stopQuietly(mc);
		lastResult = why;
		message(Component.literal(why).withStyle(ChatFormatting.RED));
	}

	static void message(Component text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.empty()
				.append(Component.literal("[Trade Reroller] ").withStyle(ChatFormatting.GOLD))
				.append(text));
		}
	}
}
