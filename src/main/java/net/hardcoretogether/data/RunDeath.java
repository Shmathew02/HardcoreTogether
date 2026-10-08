package net.hardcoretogether.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * The death that ended a run, captured at the moment of death: who died, vanilla's death message as plain text
 * (e.g. "Steve was slain by Phantom"), the dimension ID and the block position.
 *
 * damageType is the damage type ID (e.g. minecraft:fall) and killerType the entity type ID of the entity
 * responsible (e.g. minecraft:phantom), null when there is none. killerName is the killer's name when
 * killerType is minecraft:player, so the causes board can name player kills. skin is the player's "textures"
 * profile property at the moment of death, so the statue keeps that skin. Older entries may lack any of these.
 *
 * Saved as flat keys (died_uuid, died_name, death_message, death_dimension, death_pos, damage_type, killer_type,
 * killer_name, skin_textures) in current-run.json and run-history.json, all null for a command reset.
 */
public record RunDeath(UUID uuid, String name, String message, String dimension, int x, int y, int z,
		@Nullable String damageType, @Nullable String killerType, @Nullable String killerName, @Nullable SkinTextures skin) {

	/** A signed (or, offline, unsigned) "textures" profile property. */
	public record SkinTextures(String value, @Nullable String signature) {
	}

	/** A death with no killer name. */
	public RunDeath(UUID uuid, String name, String message, String dimension, int x, int y, int z,
			@Nullable String damageType, @Nullable String killerType, @Nullable SkinTextures skin) {
		this(uuid, name, message, dimension, x, y, z, damageType, killerType, null, skin);
	}

	/** A death with no killer name or skin snapshot. */
	public RunDeath(UUID uuid, String name, String message, String dimension, int x, int y, int z,
			@Nullable String damageType, @Nullable String killerType) {
		this(uuid, name, message, dimension, x, y, z, damageType, killerType, null, null);
	}

	/** A death with no cause data. */
	public RunDeath(UUID uuid, String name, String message, String dimension, int x, int y, int z) {
		this(uuid, name, message, dimension, x, y, z, null, null, null, null);
	}

	public void writeTo(JsonObject o) {
		o.addProperty("died_uuid", uuid.toString());
		o.addProperty("died_name", name);
		o.addProperty("death_message", message);
		o.addProperty("death_dimension", dimension);
		JsonArray pos = new JsonArray();
		pos.add(x);
		pos.add(y);
		pos.add(z);
		o.add("death_pos", pos);
		o.addProperty("damage_type", damageType);
		o.addProperty("killer_type", killerType);
		o.addProperty("killer_name", killerName);
		if (skin == null) {
			o.add("skin_textures", JsonNull.INSTANCE);
		} else {
			JsonObject s = new JsonObject();
			s.addProperty("value", skin.value());
			s.addProperty("signature", skin.signature());
			o.add("skin_textures", s);
		}
	}

	public static void writeNone(JsonObject o) {
		o.add("died_uuid", JsonNull.INSTANCE);
		o.add("died_name", JsonNull.INSTANCE);
		o.add("death_message", JsonNull.INSTANCE);
		o.add("death_dimension", JsonNull.INSTANCE);
		o.add("death_pos", JsonNull.INSTANCE);
		o.add("damage_type", JsonNull.INSTANCE);
		o.add("killer_type", JsonNull.INSTANCE);
		o.add("killer_name", JsonNull.INSTANCE);
		o.add("skin_textures", JsonNull.INSTANCE);
	}

	/** Null if the object has no died_uuid. Older files may lack every field after died_name. */
	public static @Nullable RunDeath readFrom(JsonObject o) {
		String uuid = str(o, "died_uuid");
		if (uuid == null) {
			return null;
		}
		String name = str(o, "died_name");
		String message = str(o, "death_message");
		String dimension = str(o, "death_dimension");
		JsonElement pos = o.get("death_pos");
		int x = 0, y = 0, z = 0;
		if (pos != null && pos.isJsonArray() && pos.getAsJsonArray().size() == 3) {
			JsonArray a = pos.getAsJsonArray();
			x = a.get(0).getAsInt();
			y = a.get(1).getAsInt();
			z = a.get(2).getAsInt();
		}
		return new RunDeath(UUID.fromString(uuid), name == null ? "" : name, message == null ? "" : message,
			dimension == null ? "" : dimension, x, y, z, str(o, "damage_type"), str(o, "killer_type"), str(o, "killer_name"),
			readSkin(o));
	}

	/** Null when missing (older entries) or empty. */
	private static @Nullable SkinTextures readSkin(JsonObject o) {
		JsonElement e = o.get("skin_textures");
		if (e == null || !e.isJsonObject()) {
			return null;
		}
		String value = str(e.getAsJsonObject(), "value");
		return value == null || value.isEmpty() ? null : new SkinTextures(value, str(e.getAsJsonObject(), "signature"));
	}

	private static @Nullable String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e instanceof JsonNull ? null : e.getAsString();
	}
}
