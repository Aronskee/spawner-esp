package com.example.spawneresp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Merkt sich alle geladenen Spawner und ermittelt regelmaessig deren Mob-Typ.
 * Laeuft nur auf dem Client-Tick, damit der Render-Code nur noch einen fertigen Snapshot liest.
 */
public final class SpawnerTracker {
	public record Target(BlockPos pos, String type) { }

	private static final Set<BlockPos> POSITIONS = ConcurrentHashMap.newKeySet();
	private static volatile List<Target> snapshot = List.of();
	private static int tickCounter = 0;

	private SpawnerTracker() { }

	public static List<Target> snapshot() {
		return snapshot;
	}

	public static void init() {
		ClientBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, level) -> {
			if (blockEntity instanceof SpawnerBlockEntity) {
				POSITIONS.add(blockEntity.getBlockPos().immutable());
			}
		});

		ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, level) -> {
			if (blockEntity instanceof SpawnerBlockEntity) {
				POSITIONS.remove(blockEntity.getBlockPos());
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			POSITIONS.clear();
			snapshot = List.of();
		});

		ClientTickEvents.END_CLIENT_TICK.register(SpawnerTracker::tick);
	}

	private static void tick(Minecraft client) {
		ClientLevel level = client.level;
		if (level == null) {
			return;
		}

		// Typ-Aufloesung nur alle 10 Ticks (0,5 s) - Spawner aendern sich praktisch nie.
		if (++tickCounter % 10 != 0) {
			return;
		}

		List<Target> result = new ArrayList<>(POSITIONS.size());

		for (BlockPos pos : POSITIONS) {
			BlockEntity be = level.getBlockEntity(pos);

			if (!(be instanceof SpawnerBlockEntity spawner)) {
				POSITIONS.remove(pos);
				continue;
			}

			result.add(new Target(pos, resolveType(level, pos, spawner)));
		}

		snapshot = List.copyOf(result);
	}

	private static String resolveType(ClientLevel level, BlockPos pos, SpawnerBlockEntity spawner) {
		try {
			Entity display = spawner.getSpawner().getOrCreateDisplayEntity(level, pos);

			if (display != null) {
				return BuiltInRegistries.ENTITY_TYPE.getKey(display.getType()).getPath();
			}
		} catch (RuntimeException ignored) {
			// Spawner-Daten noch nicht vollstaendig synchronisiert -> "unknown", wird beim naechsten Durchlauf erneut versucht.
		}

		return "unknown";
	}
}
