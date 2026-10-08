package net.hardcoretogether.reset;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;

import net.hardcoretogether.HardcoreTogether;
import net.hardcoretogether.mixin.DistanceManagerAccessor;
import net.hardcoretogether.mixin.ServerChunkCacheAccessor;
import net.hardcoretogether.mixin.TicketStorageAccessor;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Diagnostics for a DRAIN that never finishes because chunks of a reset level stay loaded. Active only while a
 * reset is in DRAIN. Logs, rate-limited:
 * - the caller (short stack) the first time each distinct code path requests a chunk from a reset level, and
 *   how many more requests each caller made, when DRAIN ends;
 * - on a DRAIN timeout, every ticket still on each reset level (type, level, positions, count) and any
 *   players the distance manager still keeps chunks loaded for.
 */
public final class DrainDiagnostics {
	private static final int STACK_FRAMES = 8;
	private static final int MAX_CALLERS_LOGGED = 10;
	private static final int MAX_TICKET_GROUPS_LOGGED = 15;
	private static final int SAMPLE_POSITIONS = 3;

	private static volatile boolean active;
	private static final Map<String, AtomicInteger> callers = new ConcurrentHashMap<>();
	private static final AtomicInteger callersLogged = new AtomicInteger();

	private DrainDiagnostics() {
	}

	public static boolean isActive() {
		return active;
	}

	static void start() {
		callers.clear();
		callersLogged.set(0);
		active = true;
	}

	/** Ends the DRAIN watch: logs request counts per caller and, after a timeout, the remaining tickets. */
	static void stop(MinecraftServer server, int runNumber, boolean timedOut) {
		if (!active) {
			return;
		}
		active = false;
		if (!callers.isEmpty()) {
			List<Map.Entry<String, AtomicInteger>> sorted = new ArrayList<>(callers.entrySet());
			sorted.sort((a, b) -> Integer.compare(b.getValue().get(), a.getValue().get()));
			HardcoreTogether.LOGGER.warn("[HT drain #{}] chunk requests on reset levels during DRAIN, by caller ({} callers):", runNumber, sorted.size());
			sorted.stream().limit(MAX_CALLERS_LOGGED).forEach(e ->
				HardcoreTogether.LOGGER.warn("[HT drain #{}]   {} requests from {}", runNumber, e.getValue().get(), firstFrame(e.getKey())));
			if (sorted.size() > MAX_CALLERS_LOGGED) {
				HardcoreTogether.LOGGER.warn("[HT drain #{}]   ... and {} more callers", runNumber, sorted.size() - MAX_CALLERS_LOGGED);
			}
		}
		if (timedOut) {
			dumpTickets(server, runNumber);
		}
		callers.clear();
	}

	/** Called from the ServerChunkCache mixin for every main-thread chunk request while DRAIN is active. */
	public static void chunkRequested(ServerLevel level, int x, int z, ChunkStatus status, boolean loadOrGenerate) {
		if (!active || !WorldSwapper.RESET_LEVELS.contains(level.dimension())) {
			return;
		}
		String stack = callerStack();
		AtomicInteger count = callers.computeIfAbsent(stack, k -> new AtomicInteger());
		if (count.getAndIncrement() == 0 && callersLogged.getAndIncrement() < MAX_CALLERS_LOGGED) {
			HardcoreTogether.LOGGER.warn("[HT drain] chunk [{}, {}] requested from {} during DRAIN (status {}, load {}), caller:\n{}",
				x, z, level.dimension().identifier(), status, loadOrGenerate, stack);
		}
	}

	/** The first frames above the chunk cache, skipping vanilla's chunk-access plumbing and this class. */
	private static String callerStack() {
		return StackWalker.getInstance().walk(frames -> frames
			.dropWhile(f -> {
				String c = f.getClassName();
				return c.startsWith(DrainDiagnostics.class.getName())
					|| c.startsWith("net.minecraft.server.level.ServerChunkCache")
					|| c.contains("ServerChunkCacheMixin")
					|| c.startsWith("net.minecraft.world.level.chunk.ChunkSource")
					|| c.equals("net.minecraft.world.level.Level")
					|| c.equals("net.minecraft.server.level.ServerLevel") && f.getMethodName().startsWith("getChunk");
			})
			.limit(STACK_FRAMES)
			.map(f -> "    at " + f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber())
			.collect(Collectors.joining("\n")));
	}

	private static String firstFrame(String stack) {
		int end = stack.indexOf('\n');
		return (end < 0 ? stack : stack.substring(0, end)).trim();
	}

	private record TicketGroup(String type, int level) {
	}

	/** Every ticket still active on each reset level, grouped by type and level, plus leftover player tracking. */
	private static void dumpTickets(MinecraftServer server, int runNumber) {
		for (ResourceKey<Level> key : WorldSwapper.RESET_LEVELS) {
			ServerLevel level = server.getLevel(key);
			if (level == null) {
				continue;
			}
			ServerChunkCacheAccessor cache = (ServerChunkCacheAccessor) level.getChunkSource();
			var tickets = ((TicketStorageAccessor) cache.hardcoreTogether$ticketStorage()).hardcoreTogether$tickets();

			Map<TicketGroup, List<ChunkPos>> groups = new LinkedHashMap<>();
			for (var entry : tickets.long2ObjectEntrySet()) {
				ChunkPos pos = ChunkPos.unpack(entry.getLongKey());
				for (Ticket ticket : entry.getValue()) {
					Identifier type = BuiltInRegistries.TICKET_TYPE.getKey(ticket.getType());
					groups.computeIfAbsent(new TicketGroup(type == null ? "?" : type.toString(), ticket.getTicketLevel()), k -> new ArrayList<>()).add(pos);
				}
			}
			HardcoreTogether.LOGGER.warn("[HT drain #{}] {}: {} loaded chunks, {} chunks with tickets, {} ticket groups",
				runNumber, key.identifier(), level.getChunkSource().getLoadedChunksCount(), tickets.size(), groups.size());
			groups.entrySet().stream()
				.sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
				.limit(MAX_TICKET_GROUPS_LOGGED)
				.forEach(e -> HardcoreTogether.LOGGER.warn("[HT drain #{}]   ticket {} level {}: {} chunk(s), e.g. {}",
					runNumber, e.getKey().type(), e.getKey().level(), e.getValue().size(),
					e.getValue().stream().limit(SAMPLE_POSITIONS).map(ChunkPos::toString).collect(Collectors.joining(" "))));
			if (groups.size() > MAX_TICKET_GROUPS_LOGGED) {
				HardcoreTogether.LOGGER.warn("[HT drain #{}]   ... and {} more ticket groups", runNumber, groups.size() - MAX_TICKET_GROUPS_LOGGED);
			}

			Long2ObjectMap<ObjectSet<ServerPlayer>> players =
				((DistanceManagerAccessor) cache.hardcoreTogether$distanceManager()).hardcoreTogether$playersPerChunk();
			if (!players.isEmpty()) {
				Set<String> names = players.values().stream().flatMap(Set::stream)
					.map(p -> p.getGameProfile().name() + (p.isRemoved() ? " (removed object)" : "")).collect(Collectors.toSet());
				HardcoreTogether.LOGGER.warn("[HT drain #{}]   distance manager still tracks players in {} chunk(s): {}",
					runNumber, players.size(), names);
			}
		}
	}
}
