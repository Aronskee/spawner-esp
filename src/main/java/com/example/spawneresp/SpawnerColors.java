package com.example.spawneresp;

import java.util.Map;

/** Farben pro Spawner-Typ (RGB 0..1). Unbekannte Typen bekommen eine stabile Farbe aus dem Namen. */
public final class SpawnerColors {
	private static final Map<String, float[]> FIXED = Map.ofEntries(
		Map.entry("zombie",       new float[] {0.25f, 0.90f, 0.25f}),
		Map.entry("skeleton",     new float[] {0.92f, 0.92f, 0.92f}),
		Map.entry("spider",       new float[] {0.95f, 0.15f, 0.15f}),
		Map.entry("cave_spider",  new float[] {0.10f, 0.85f, 0.85f}),
		Map.entry("blaze",        new float[] {1.00f, 0.60f, 0.05f}),
		Map.entry("silverfish",   new float[] {0.55f, 0.60f, 0.80f}),
		Map.entry("magma_cube",   new float[] {0.80f, 0.30f, 0.05f}),
		Map.entry("slime",        new float[] {0.45f, 1.00f, 0.45f}),
		Map.entry("creeper",      new float[] {0.10f, 0.60f, 0.10f}),
		Map.entry("husk",         new float[] {0.80f, 0.70f, 0.40f}),
		Map.entry("stray",        new float[] {0.50f, 0.80f, 1.00f}),
		Map.entry("bogged",       new float[] {0.35f, 0.55f, 0.20f}),
		Map.entry("breeze",       new float[] {0.65f, 0.75f, 1.00f}),
		Map.entry("unknown",      new float[] {1.00f, 0.00f, 1.00f})
	);

	private SpawnerColors() { }

	public static float[] forType(String type) {
		float[] fixed = FIXED.get(type);

		if (fixed != null) {
			return fixed;
		}

		// Stabile Farbe aus dem Namen (HSV -> RGB)
		float hue = (Math.floorMod(type.hashCode(), 360)) / 360f;
		return hsvToRgb(hue, 0.85f, 1.0f);
	}

	private static float[] hsvToRgb(float h, float s, float v) {
		float c = v * s;
		float x = c * (1 - Math.abs((h * 6) % 2 - 1));
		float m = v - c;
		float r, g, b;

		int sector = (int) (h * 6);
		switch (sector) {
			case 0 -> { r = c; g = x; b = 0; }
			case 1 -> { r = x; g = c; b = 0; }
			case 2 -> { r = 0; g = c; b = x; }
			case 3 -> { r = 0; g = x; b = c; }
			case 4 -> { r = x; g = 0; b = c; }
			default -> { r = c; g = 0; b = x; }
		}

		return new float[] {r + m, g + m, b + m};
	}
}
