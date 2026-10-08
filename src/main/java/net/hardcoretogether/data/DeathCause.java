package net.hardcoretogether.data;

import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The short cause label for the causes board ("Drowning", "Creeper", "Steve"), derived from a death record. Order:
 * the attacker (mob type, or the player's name), then the damage type, then (older records) the death
 * message. A death nothing can classify is "Other". No Minecraft classes, so it is unit tested directly.
 */
public final class DeathCause {
	public static final String OTHER = "Other";

	/** Vanilla damage type ids (path only) to labels; anything not listed is shown as its prettified id. */
	private static final Map<String, String> DAMAGE = Map.ofEntries(
		Map.entry("drown", "Drowning"),
		Map.entry("fall", "Falling"),
		Map.entry("ender_pearl", "Falling"),
		Map.entry("in_fire", "Fire"),
		Map.entry("on_fire", "Fire"),
		Map.entry("campfire", "Fire"),
		Map.entry("lava", "Lava"),
		Map.entry("hot_floor", "Magma"),
		Map.entry("starve", "Starvation"),
		Map.entry("in_wall", "Suffocation"),
		Map.entry("cramming", "Suffocation"),
		Map.entry("explosion", "Explosion"),
		Map.entry("player_explosion", "Explosion"),
		Map.entry("bad_respawn_point", "Explosion"),
		Map.entry("fireworks", "Explosion"),
		Map.entry("cactus", "Cactus"),
		Map.entry("sweet_berry_bush", "Berry bush"),
		Map.entry("freeze", "Freezing"),
		Map.entry("lightning_bolt", "Lightning"),
		Map.entry("magic", "Magic"),
		Map.entry("indirect_magic", "Magic"),
		Map.entry("wither", "Wither"),
		Map.entry("wither_skull", "Wither"),
		Map.entry("out_of_world", "Void"),
		Map.entry("outside_border", "World border"),
		Map.entry("fly_into_wall", "Kinetic energy"),
		Map.entry("stalagmite", "Dripstone"),
		Map.entry("falling_stalactite", "Dripstone"),
		Map.entry("falling_block", "Falling block"),
		Map.entry("falling_anvil", "Falling block"),
		Map.entry("dragon_breath", "Dragon's breath"),
		Map.entry("dry_out", "Drying out"),
		Map.entry("generic", OTHER),
		Map.entry("generic_kill", OTHER),
		Map.entry("mob_attack", OTHER),
		Map.entry("mob_attack_no_aggro", OTHER),
		Map.entry("player_attack", OTHER),
		Map.entry("arrow", OTHER),
		Map.entry("trident", OTHER),
		Map.entry("mob_projectile", OTHER),
		Map.entry("thrown", OTHER),
		Map.entry("sting", OTHER));

	/** "<victim> was slain by Zombie using [Sword] whilst ..." style messages: group 1 is the attacker. */
	private static final Pattern BY_ATTACKER = Pattern.compile("^(?:was )?(?:slain|shot|blown up|killed|fireballed|pummeled|impaled|"
		+ "skewered|stung|squashed|obliterated|frozen to death|struck|squished|poked to death|doomed to fall|burned to death|"
		+ "burnt to a crisp|pricked to death|withered away|fell too far|knocked into the void|walked into fire|walked into danger zone)"
		+ "(?: whilst fighting| while fighting)? by (.+?)(?: using .*| whilst .*| while .*|)$");

	/** Environment messages without an attacker, matched in this order (substrings of the lower-case message). */
	private static final String[][] MESSAGE = {
		{"fell out of the world", "Void"}, {"didn't want to live in the same world", "Void"},
		{"drowned", "Drowning"},
		{"tried to swim in lava", "Lava"}, {"discovered the floor was lava", "Magma"}, {"walked into the danger zone", "Magma"},
		{"went up in flames", "Fire"}, {"burned to death", "Fire"}, {"burnt to a crisp", "Fire"}, {"walked into fire", "Fire"},
		{"starved to death", "Starvation"},
		{"suffocated in a wall", "Suffocation"}, {"was squished too much", "Suffocation"},
		{"blew up", "Explosion"}, {"was blown up", "Explosion"},
		{"froze to death", "Freezing"}, {"was struck by lightning", "Lightning"},
		{"was pricked to death", "Cactus"}, {"was poked to death by a sweet berry bush", "Berry bush"},
		{"withered away", "Wither"}, {"experienced kinetic energy", "Kinetic energy"},
		{"was skewered by a falling stalactite", "Dripstone"}, {"was impaled on a stalagmite", "Dripstone"},
		{"was squashed by a falling", "Falling block"},
		{"hit the ground too hard", "Falling"}, {"fell from a high place", "Falling"}, {"fell off", "Falling"},
		{"fell while", "Falling"}, {"fell into", "Falling"}, {"fell out of the water", "Falling"}, {"fell too far", "Falling"},
		{"was killed by magic", "Magic"}, {"was killed by even more magic", "Magic"},
	};

	private DeathCause() {
	}

	public static String label(RunDeath d) {
		return label(d.damageType(), d.killerType(), d.killerName(), d.message(), d.name());
	}

	/**
	 * The label from the stored fields. killerType "minecraft:player" uses killerName (or the name after "by" in
	 * the message); a player who killed themselves (own TNT, own arrow) counts by the damage type instead.
	 */
	public static String label(@Nullable String damageType, @Nullable String killerType, @Nullable String killerName,
			@Nullable String message, @Nullable String victim) {
		boolean self = killerName != null && killerName.equals(victim);
		if (killerType != null && !self) {
			if (killerType.equals("minecraft:player")) {
				if (killerName != null && !killerName.isEmpty()) {
					return killerName;
				}
				String fromMessage = attacker(message, victim);
				return fromMessage != null ? fromMessage : "Player";
			}
			return prettify(killerType);
		}
		if (damageType != null) {
			String path = damageType.substring(damageType.indexOf(':') + 1);
			String mapped = DAMAGE.get(path);
			if (mapped != null && !mapped.equals(OTHER)) {
				return mapped;
			}
			if (mapped == null) {
				return prettify(damageType);
			}
			// An attack damage type without an attacker: the message may still name one.
		}
		return fromMessage(message, victim);
	}

	/** For older records with no attacker or damage type: the label read from the death message, or Other. */
	public static String fromMessage(@Nullable String message, @Nullable String victim) {
		if (message == null || message.isBlank()) {
			return OTHER;
		}
		String attacker = attacker(message, victim);
		if (attacker != null) {
			return attacker;
		}
		String m = withoutVictim(message, victim).toLowerCase(Locale.ROOT);
		for (String[] rule : MESSAGE) {
			if (m.contains(rule[0])) {
				return rule[1];
			}
		}
		return OTHER;
	}

	/** The attacker named in a "was slain by X" style message, without "using [...]" or "whilst ...". */
	static @Nullable String attacker(@Nullable String message, @Nullable String victim) {
		if (message == null) {
			return null;
		}
		Matcher m = BY_ATTACKER.matcher(withoutVictim(message, victim));
		if (!m.matches()) {
			return null;
		}
		String who = m.group(1).trim();
		if (who.startsWith("a ") || who.startsWith("an ")) {
			return null; // "by a falling anvil", "by a sweet berry bush": environment, not an attacker
		}
		return who.isEmpty() ? null : who;
	}

	private static String withoutVictim(String message, @Nullable String victim) {
		String m = message.trim();
		if (victim != null && !victim.isEmpty() && m.startsWith(victim + " ")) {
			m = m.substring(victim.length() + 1).trim();
		}
		return m;
	}

	/** "minecraft:wither_skeleton" -> "Wither Skeleton". */
	static String prettify(String id) {
		String path = id.substring(id.indexOf(':') + 1);
		StringBuilder out = new StringBuilder();
		for (String word : path.split("_")) {
			if (!word.isEmpty()) {
				if (!out.isEmpty()) {
					out.append(' ');
				}
				out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
			}
		}
		return out.isEmpty() ? OTHER : out.toString();
	}
}
