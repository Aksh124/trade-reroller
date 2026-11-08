package tradereroller;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** What the settings screen edits, kept in config/tradereroller.properties. */
public final class Settings {
	private static final Logger LOGGER = LoggerFactory.getLogger("tradereroller");
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("tradereroller.properties");

	/** The last lectern used, or null. */
	public static BlockPos lectern;
	/** Enchantment id, like minecraft:mending. */
	public static String enchantment = "minecraft:mending";
	/** Lowest acceptable level; 0 means the enchantment's highest level. */
	public static int minLevel;
	/** Most emeralds to pay; 0 means any price. */
	public static int maxPrice;
	/** Stop before the axe gets this close to breaking. */
	public static int axeUsesToKeep = 10;
	/** A chat line for every roll. */
	public static boolean rollMessages = true;
	/** A sound when the book is found. */
	public static boolean foundSound = true;

	private Settings() {}

	public static void load() {
		if (!Files.exists(FILE)) {
			return;
		}
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
			properties.load(reader);
		} catch (IOException e) {
			LOGGER.warn("Couldn't read {}", FILE, e);
			return;
		}
		String[] pos = properties.getProperty("lectern", "").split(",");
		if (pos.length == 3) {
			try {
				lectern = new BlockPos(Integer.parseInt(pos[0].trim()), Integer.parseInt(pos[1].trim()), Integer.parseInt(pos[2].trim()));
			} catch (NumberFormatException ignored) {
				lectern = null;
			}
		}
		enchantment = properties.getProperty("enchantment", enchantment);
		minLevel = number(properties, "minLevel", minLevel, 0, 255);
		maxPrice = number(properties, "maxPrice", maxPrice, 0, 64);
		axeUsesToKeep = number(properties, "axeUsesToKeep", axeUsesToKeep, 1, 2000);
		rollMessages = Boolean.parseBoolean(properties.getProperty("rollMessages", Boolean.toString(rollMessages)));
		foundSound = Boolean.parseBoolean(properties.getProperty("foundSound", Boolean.toString(foundSound)));
	}

	public static void save() {
		Properties properties = new Properties();
		if (lectern != null) {
			properties.setProperty("lectern", lectern.getX() + "," + lectern.getY() + "," + lectern.getZ());
		}
		properties.setProperty("enchantment", enchantment);
		properties.setProperty("minLevel", Integer.toString(minLevel));
		properties.setProperty("maxPrice", Integer.toString(maxPrice));
		properties.setProperty("axeUsesToKeep", Integer.toString(axeUsesToKeep));
		properties.setProperty("rollMessages", Boolean.toString(rollMessages));
		properties.setProperty("foundSound", Boolean.toString(foundSound));
		try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
			properties.store(writer, "Trade Reroller. minLevel 0 = highest level, maxPrice 0 = any price");
		} catch (IOException e) {
			LOGGER.warn("Couldn't save {}", FILE, e);
		}
	}

	private static int number(Properties properties, String key, int fallback, int min, int max) {
		try {
			int value = Integer.parseInt(properties.getProperty(key, "").trim());
			return value >= min && value <= max ? value : fallback;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
