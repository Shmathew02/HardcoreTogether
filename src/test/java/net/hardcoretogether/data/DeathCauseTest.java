package net.hardcoretogether.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeathCauseTest {
	private static String label(String damage, String killer, String killerName, String message) {
		return DeathCause.label(damage, killer, killerName, message, "Alex");
	}

	@Test
	void environmentFromTheDamageType() {
		assertEquals("Drowning", label("minecraft:drown", null, null, "Alex drowned"));
		assertEquals("Falling", label("minecraft:fall", null, null, "Alex fell from a high place"));
		assertEquals("Fire", label("minecraft:in_fire", null, null, "Alex went up in flames"));
		assertEquals("Fire", label("minecraft:on_fire", null, null, "Alex burned to death"));
		assertEquals("Lava", label("minecraft:lava", null, null, "Alex tried to swim in lava"));
		assertEquals("Starvation", label("minecraft:starve", null, null, "Alex starved to death"));
		assertEquals("Suffocation", label("minecraft:in_wall", null, null, "Alex suffocated in a wall"));
		assertEquals("Explosion", label("minecraft:explosion", null, null, "Alex blew up"));
		assertEquals("Falling", label("minecraft:fall", null, null, "Alex hit the ground too hard whilst trying to escape Zombie"));
	}

	@Test
	void attackersByMobTypeOrPlayerName() {
		assertEquals("Creeper", label("minecraft:explosion", "minecraft:creeper", null, "Alex was blown up by Creeper"));
		assertEquals("Creeper", label("minecraft:explosion", "minecraft:creeper", null, "Alex was blown up by Creeper whilst fighting Zombie"));
		assertEquals("Zombie", label("minecraft:mob_attack", "minecraft:zombie", null, "Alex was slain by Zombie"));
		assertEquals("Skeleton", label("minecraft:arrow", "minecraft:skeleton", null, "Alex was shot by Skeleton"));
		assertEquals("Wither Skeleton", label("minecraft:mob_attack", "minecraft:wither_skeleton", null, "Alex was slain by Wither Skeleton"));
		assertEquals("Steve", label("minecraft:player_attack", "minecraft:player", "Steve", "Alex was slain by Steve using [Sword]"));
		assertEquals("Steve", label("minecraft:player_attack", "minecraft:player", null, "Alex was slain by Steve using [Sword]"));
		// Your own TNT: counted as an explosion, not as yourself.
		assertEquals("Explosion", label("minecraft:player_explosion", "minecraft:player", "Alex", "Alex was blown up by Alex"));
	}

	@Test
	void oldRecordsFromTheMessage() {
		assertEquals("Drowning", DeathCause.fromMessage("Alex drowned", "Alex"));
		assertEquals("Falling", DeathCause.fromMessage("Alex fell from a high place", "Alex"));
		assertEquals("Creeper", DeathCause.fromMessage("Alex was blown up by Creeper", "Alex"));
		assertEquals("Creeper", DeathCause.fromMessage("Alex was blown up by Creeper whilst fighting Zombie", "Alex"));
		assertEquals("Zombie", DeathCause.fromMessage("Alex was slain by Zombie", "Alex"));
		assertEquals("Skeleton", DeathCause.fromMessage("Alex was shot by Skeleton", "Alex"));
		assertEquals("Steve", DeathCause.fromMessage("Alex was slain by Steve using [Diamond Sword]", "Alex"));
		assertEquals("Lava", DeathCause.fromMessage("Alex tried to swim in lava to escape Zombie", "Alex"));
		assertEquals("Fire", DeathCause.fromMessage("Alex went up in flames", "Alex"));
		assertEquals("Starvation", DeathCause.fromMessage("Alex starved to death", "Alex"));
		assertEquals("Suffocation", DeathCause.fromMessage("Alex suffocated in a wall", "Alex"));
		assertEquals("Explosion", DeathCause.fromMessage("Alex blew up", "Alex"));
		assertEquals("Void", DeathCause.fromMessage("Alex fell out of the world", "Alex"));
		assertEquals("Dripstone", DeathCause.fromMessage("Alex was skewered by a falling stalactite", "Alex"));
		assertEquals(DeathCause.OTHER, DeathCause.fromMessage("Alex died", "Alex"));
		assertEquals(DeathCause.OTHER, DeathCause.fromMessage("", "Alex"));
	}

	@Test
	void attackDamageWithoutAttackerFallsBackToTheMessage() {
		assertEquals("Zombie", label("minecraft:mob_attack", null, null, "Alex was slain by Zombie"));
		assertEquals(DeathCause.OTHER, label("minecraft:generic", null, null, "Alex died"));
		assertEquals("Sonic Boom", label("minecraft:sonic_boom", null, null, "Alex was obliterated by a sonically-charged shriek"));
	}
}
