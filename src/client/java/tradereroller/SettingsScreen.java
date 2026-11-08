package tradereroller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * Everything /reroll start takes, as a screen: the enchantment from a searchable list, the
 * lectern, level, price and axe limit, plus Start and Stop.
 */
public final class SettingsScreen extends Screen {
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA0A0A0;
	private static final int RED = 0xFFFF5555;

	private EditBox search;
	private EnchantmentList list;
	private EditBox lecternX;
	private EditBox lecternY;
	private EditBox lecternZ;
	private Button useLookedAt;
	private Button levelButton;
	private EditBox maxPrice;
	private EditBox axeUses;
	private Button startStop;

	private final Screen parent;
	private Holder<Enchantment> enchantment;
	private int level;
	private String error;
	private boolean loaded;
	/** Opened from Mod Menu on the title screen there's no world, so no enchantment list. */
	private boolean inWorld;

	public SettingsScreen() {
		this(null);
	}

	public SettingsScreen(Screen parent) {
		super(Component.literal("Trade Reroller"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		Minecraft mc = Minecraft.getInstance();
		int left = width / 2 - 210;
		int right = width / 2 + 10;
		inWorld = mc.level != null;
		boolean first = !loaded;
		loaded = true;
		if (first) {
			level = Settings.minLevel;
		}
		if (first && inWorld) {
			enchantment = TradeRerollerClient.enchantments(mc).listElements()
				.filter(holder -> holder.key().identifier().toString().equals(Settings.enchantment))
				.findFirst().map(holder -> (Holder<Enchantment>) holder).orElse(null);
		}

		// Left: the enchantment.
		search = addRenderableWidget(new EditBox(font, left, 26, 200, 20, Component.literal("Search")));
		search.setHint(Component.literal("Search enchantments").withStyle(ChatFormatting.DARK_GRAY));
		search.setEditable(inWorld);
		list = addRenderableWidget(new EnchantmentList(mc, 200, height - 50 - 54, 50));
		list.setX(left);
		search.setResponder(text -> list.filter(text));
		list.filter("");

		// Right: the lectern and limits.
		BlockPos lectern = Settings.lectern;
		BlockPos lookedAt = TradeRerollerClient.lookedAtLectern(mc);
		if (first && lookedAt != null) {
			lectern = lookedAt;
		}
		lecternX = addRenderableWidget(numberBox(right, 36, 64, "X", lectern == null ? "" : Integer.toString(lectern.getX())));
		lecternY = addRenderableWidget(numberBox(right + 68, 36, 64, "Y", lectern == null ? "" : Integer.toString(lectern.getY())));
		lecternZ = addRenderableWidget(numberBox(right + 136, 36, 64, "Z", lectern == null ? "" : Integer.toString(lectern.getZ())));
		useLookedAt = addRenderableWidget(Button.builder(Component.literal("Use the lectern I'm looking at"), button -> {
			BlockPos pos = TradeRerollerClient.lookedAtLectern(mc);
			if (pos != null) {
				lecternX.setValue(Integer.toString(pos.getX()));
				lecternY.setValue(Integer.toString(pos.getY()));
				lecternZ.setValue(Integer.toString(pos.getZ()));
			}
		}).bounds(right, 58, 200, 20).build());
		useLookedAt.active = lookedAt != null;
		useLookedAt.setTooltip(Tooltip.create(Component.literal(lookedAt == null
			? "Look at a lectern (or the block under it) before opening this screen."
			: "The lectern at " + lookedAt.toShortString() + ".")));

		levelButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
			int max = enchantment == null ? 1 : enchantment.value().getMaxLevel();
			level = level >= max ? 0 : level + 1;
			updateLevelButton();
		}).bounds(right, 80, 200, 20).build());
		levelButton.setTooltip(Tooltip.create(Component.literal("The lowest level that counts as a find. Click to change.")));
		updateLevelButton();

		maxPrice = addRenderableWidget(numberBox(right + 120, 102, 80, "Max emeralds", Settings.maxPrice == 0 ? "" : Integer.toString(Settings.maxPrice)));
		maxPrice.setHint(Component.literal("any").withStyle(ChatFormatting.DARK_GRAY));
		maxPrice.setTooltip(Tooltip.create(Component.literal("The most emeralds the book can cost, 1 to 64. Leave empty for any price.")));
		axeUses = addRenderableWidget(numberBox(right + 120, 124, 80, "Keep axe uses", Integer.toString(Settings.axeUsesToKeep)));
		axeUses.setTooltip(Tooltip.create(Component.literal("Stops before the axe has this few uses left, so it never breaks.")));

		addRenderableWidget(CycleButton.onOffBuilder(Settings.rollMessages)
			.create(right, 146, 200, 20, Component.literal("Message every roll"), (button, value) -> Settings.rollMessages = value));
		addRenderableWidget(CycleButton.onOffBuilder(Settings.foundSound)
			.create(right, 168, 200, 20, Component.literal("Sound when found"), (button, value) -> Settings.foundSound = value));

		// Bottom.
		startStop = addRenderableWidget(Button.builder(Component.empty(), button -> startOrStop())
			.bounds(width / 2 - 155, height - 26, 150, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
			.bounds(width / 2 + 5, height - 26, 150, 20).build());
		startStop.active = inWorld;
		updateStartStop();
		setInitialFocus(search);
	}

	private EditBox numberBox(int x, int y, int boxWidth, String name, String value) {
		EditBox box = new EditBox(font, x, y, boxWidth, 20, Component.literal(name));
		box.setMaxLength(9);
		box.setValue(value);
		box.setHint(Component.literal(name).withStyle(ChatFormatting.DARK_GRAY));
		box.setResponder(text -> error = null);
		return box;
	}

	@Override
	public void tick() {
		updateStartStop();
	}

	private void updateStartStop() {
		startStop.setMessage(Component.literal(Reroller.step() == null ? "Start rerolling" : "Stop rerolling"));
	}

	private void updateLevelButton() {
		levelButton.active = enchantment != null;
		if (enchantment == null) {
			levelButton.setMessage(Component.literal("Level: ").append(level == 0 ? Component.literal("highest")
				: roman(level).copy().append(" or higher")));
			return;
		}
		int max = enchantment.value().getMaxLevel();
		if (level > max) {
			level = 0;
		}
		MutableComponent text = Component.literal("Level: ");
		if (level == 0 || level == max) {
			text.append("highest (").append(roman(max)).append(")");
		} else {
			text.append(roman(level)).append(" or higher");
		}
		levelButton.setMessage(text);
	}

	private static Component roman(int level) {
		return Component.translatable("enchantment.level." + level);
	}

	private void startOrStop() {
		Minecraft mc = Minecraft.getInstance();
		if (Reroller.step() != null) {
			Reroller.stop(mc);
			updateStartStop();
			return;
		}
		if (!apply()) {
			return;
		}
		if (Settings.lectern == null) {
			error = "Set the lectern's coordinates, or look at it and use the button.";
			return;
		}
		String problem = TradeRerollerClient.start(mc, Settings.lectern, Settings.enchantment, Settings.minLevel, Settings.maxPrice);
		if (problem != null) {
			error = problem;
			return;
		}
		// Rerolling needs the screen closed, so the trade screen can open.
		mc.gui.setScreen(null);
	}

	/** Copies the fields into Settings and saves them. Returns false and shows why if something's wrong. */
	private boolean apply() {
		if (enchantment == null && inWorld) {
			error = "Pick an enchantment from the list.";
			return false;
		}
		BlockPos lectern = null;
		String x = lecternX.getValue().trim();
		String y = lecternY.getValue().trim();
		String z = lecternZ.getValue().trim();
		if (!x.isEmpty() || !y.isEmpty() || !z.isEmpty()) {
			try {
				lectern = new BlockPos(Integer.parseInt(x), Integer.parseInt(y), Integer.parseInt(z));
			} catch (NumberFormatException e) {
				error = "The lectern needs whole-number X, Y and Z.";
				return false;
			}
		}
		Integer price = parse(maxPrice.getValue(), 0, 1, 64);
		if (price == null) {
			error = "Max emeralds must be 1 to 64, or empty for any price.";
			return false;
		}
		Integer keep = parse(axeUses.getValue(), 10, 1, 2000);
		if (keep == null) {
			error = "Keep axe uses must be 1 to 2000.";
			return false;
		}
		Settings.lectern = lectern;
		if (enchantment != null) {
			Settings.enchantment = enchantment.unwrapKey().orElseThrow().identifier().toString();
		}
		Settings.minLevel = level;
		Settings.maxPrice = price;
		Settings.axeUsesToKeep = keep;
		Settings.save();
		return true;
	}

	private static Integer parse(String text, int empty, int min, int max) {
		text = text.trim();
		if (text.isEmpty()) {
			return empty;
		}
		try {
			int value = Integer.parseInt(text);
			return value >= min && value <= max ? value : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	@Override
	public void onClose() {
		if (apply()) {
			minecraft.gui.setScreen(parent);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int right = width / 2 + 10;
		graphics.centeredText(font, title, width / 2, 10, WHITE);
		graphics.text(font, Component.literal("Lectern (or the block under it)"), right, 26, GRAY);
		graphics.text(font, Component.literal("Max emeralds"), right, 108, WHITE);
		graphics.text(font, Component.literal("Keep axe uses"), right, 130, WHITE);
		if (!inWorld) {
			graphics.textWithWordWrap(font, Component.literal("Join a world to choose the enchantment and start. Saved: " + Settings.enchantment),
				width / 2 - 204, 56, 188, GRAY);
		}
		String status = error != null ? error : TradeRerollerClient.statusText();
		graphics.textWithWordWrap(font, Component.literal(status), width / 2 - 210, height - 46, 420, error != null ? RED : GRAY);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// For the game test.

	public EditBox searchBox() {
		return search;
	}

	public List<String> shownEnchantments() {
		List<String> ids = new ArrayList<>();
		for (EnchantmentList.Row row : list.children()) {
			ids.add(row.holder.unwrapKey().orElseThrow().identifier().toString());
		}
		return ids;
	}

	public void pick(int row) {
		list.pick(list.children().get(row));
	}

	public Button levelButton() {
		return levelButton;
	}

	public EditBox maxPriceBox() {
		return maxPrice;
	}

	public Button startStopButton() {
		return startStop;
	}

	public String error() {
		return error;
	}

	final class EnchantmentList extends ObjectSelectionList<EnchantmentList.Row> {
		EnchantmentList(Minecraft mc, int listWidth, int listHeight, int y) {
			super(mc, listWidth, listHeight, y, 14);
		}

		void filter(String text) {
			String query = text.trim().toLowerCase(Locale.ROOT);
			List<Row> rows = new ArrayList<>();
			if (minecraft.level == null) {
				replaceEntries(rows);
				return;
			}
			TradeRerollerClient.enchantments(minecraft).listElements().forEach(holder -> {
				String name = holder.value().description().getString().toLowerCase(Locale.ROOT);
				if (query.isEmpty() || name.contains(query) || holder.key().identifier().toString().contains(query)) {
					rows.add(new Row(holder));
				}
			});
			rows.sort(Comparator.comparing(row -> row.holder.value().description().getString()));
			replaceEntries(rows);
			for (Row row : rows) {
				if (enchantment != null && row.holder.is(enchantment)) {
					setSelected(row);
					scrollToEntry(row);
				}
			}
		}

		void pick(Row row) {
			setSelected(row);
			enchantment = row.holder;
			error = null;
			updateLevelButton();
		}

		@Override
		public int getRowWidth() {
			return width - 12;
		}

		final class Row extends ObjectSelectionList.Entry<Row> {
			final Holder<Enchantment> holder;

			Row(Holder<Enchantment> holder) {
				this.holder = holder;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int max = holder.value().getMaxLevel();
				Component name = holder.value().description();
				graphics.text(font, name, getContentX() + 2, getContentY() + 2, WHITE);
				if (max > 1) {
					graphics.text(font, roman(max), getContentRight() - font.width(roman(max)) - 2, getContentY() + 2, GRAY);
				}
			}

			@Override
			public Component getNarration() {
				return holder.value().description();
			}

			@Override
			public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
				pick(this);
				return true;
			}
		}
	}
}
