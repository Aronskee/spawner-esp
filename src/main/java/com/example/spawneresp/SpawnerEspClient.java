package com.example.spawneresp;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/**
 * Spawner ESP fuer Minecraft 26.2.
 * Basiert auf dem offiziellen Fabric-Beispiel "Rendering in the World" (Extraction-/Drawing-Phase,
 * eigene RenderPipeline ohne Depth-Test = sichtbar durch Waende).
 */
public class SpawnerEspClient implements ClientModInitializer {
	public static final String MOD_ID = "spawneresp";

	/** Maximale Entfernung in Bloecken, bis zu der Spawner angezeigt werden. */
	private static final double MAX_DISTANCE = 128.0;

	private static final float BOX_ALPHA = 0.22f;
	private static final float EDGE_ALPHA = 1.0f;
	private static final float TRACER_ALPHA = 0.85f;

	/** Pipeline = DEBUG_FILLED (Position + Farbe, Quads), aber ohne Tiefentest. */
	private static final RenderPipeline THROUGH_WALLS = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/spawner_esp_through_walls"))
			.withDepthStencilState(Optional.empty())
			.build()
	);

	private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
	private static final Vector3f MODEL_OFFSET = new Vector3f();
	private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

	private static StagedVertexBuffer stagedBuffer;

	// Wird in der Extraction-Phase befuellt und in der Drawing-Phase gelesen.
	private volatile List<EspTarget> targets = List.of();
	private volatile Vec3 lookDir = new Vec3(0, 0, 1);

	private static final int[][] EDGES = {
		{0, 1}, {1, 3}, {3, 2}, {2, 0},   // unten
		{4, 5}, {5, 7}, {7, 6}, {6, 4},   // oben
		{0, 4}, {1, 5}, {2, 6}, {3, 7}    // Pfeiler
	};

	@Override
	public void onInitializeClient() {
		SpawnerTracker.init();

		LevelExtractionEvents.END_EXTRACTION.register(this::extract);
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(this::draw);

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			if (stagedBuffer != null) {
				stagedBuffer.close();
				stagedBuffer = null;
			}
		});
	}

	// ---------------------------------------------------------------- Extraction

	private void extract(LevelExtractionContext context) {
		Minecraft mc = Minecraft.getInstance();

		if (mc.player == null || mc.level == null) {
			this.targets = List.of();
			return;
		}

		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		this.lookDir = mc.player.getViewVector(partialTick);

		Vec3 playerPos = mc.player.position();
		double maxSq = MAX_DISTANCE * MAX_DISTANCE;
		List<EspTarget> list = new ArrayList<>();

		for (SpawnerTracker.Target t : SpawnerTracker.snapshot()) {
			if (t.pos().distToCenterSqr(playerPos.x, playerPos.y, playerPos.z) > maxSq) {
				continue;
			}

			float[] c = SpawnerColors.forType(t.type());
			list.add(new EspTarget(t.pos().getX(), t.pos().getY(), t.pos().getZ(), c[0], c[1], c[2]));
		}

		this.targets = list;
	}

	// ---------------------------------------------------------------- Drawing

	private void draw(LevelRenderContext context) {
		List<EspTarget> current = this.targets;

		if (current.isEmpty()) {
			return;
		}

		if (stagedBuffer == null) {
			stagedBuffer = new StagedVertexBuffer(() -> MOD_ID + " buffer", RenderType.SMALL_BUFFER_SIZE);
		}

		RenderPipeline pipeline = THROUGH_WALLS;
		VertexFormat format = pipeline.getVertexFormatBinding(0);
		assert format != null;

		PrimitiveTopology topology = pipeline.getPrimitiveTopology();
		StagedVertexBuffer.Draw drawCall = stagedBuffer.appendDraw(
			format,
			topology,
			topology == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null
		);

		PoseStack poseStack = context.poseStack();
		Vec3 cam = context.levelState().cameraRenderState.pos;

		poseStack.pushPose();
		poseStack.translate(-cam.x, -cam.y, -cam.z);

		VertexConsumer buffer = stagedBuffer.getVertexBuilder(drawCall);
		Matrix4fc matrix = poseStack.last().pose();

		Vec3 tracerStart = cam.add(this.lookDir.scale(0.6));

		for (EspTarget t : current) {
			emitTarget(matrix, buffer, cam, tracerStart, t);
		}

		poseStack.popPose();

		stagedBuffer.upload();

		StagedVertexBuffer.ExecuteInfo info = stagedBuffer.getExecuteInfo(drawCall);

		if (info != null) {
			execute(Minecraft.getInstance(), info, pipeline);
		}

		stagedBuffer.endFrame();
	}

	private static void emitTarget(Matrix4fc m, VertexConsumer buf, Vec3 cam, Vec3 tracerStart, EspTarget t) {
		final double e = 0.003; // minimal groesser als der Block, gegen Z-Fighting beim Rand
		double minX = t.x - e, minY = t.y - e, minZ = t.z - e;
		double maxX = t.x + 1 + e, maxY = t.y + 1 + e, maxZ = t.z + 1 + e;

		filledBox(m, buf, minX, minY, minZ, maxX, maxY, maxZ, t.r, t.g, t.b, BOX_ALPHA);

		// 8 Ecken: Bit0 = x, Bit1 = z, Bit2 = y
		Vec3[] corners = new Vec3[8];
		for (int i = 0; i < 8; i++) {
			corners[i] = new Vec3(
				(i & 1) == 0 ? minX : maxX,
				(i & 4) == 0 ? minY : maxY,
				(i & 2) == 0 ? minZ : maxZ
			);
		}

		for (int[] edge : EDGES) {
			ribbon(m, buf, cam, corners[edge[0]], corners[edge[1]], 0.0012, 0.006, t.r, t.g, t.b, EDGE_ALPHA);
		}

		// Tracer: von (leicht vor) der Kamera zur Spawner-Mitte
		Vec3 center = new Vec3(t.x + 0.5, t.y + 0.5, t.z + 0.5);

		if (center.distanceTo(cam) > 1.5) {
			ribbon(m, buf, cam, tracerStart, center, 0.0009, 0.004, t.r, t.g, t.b, TRACER_ALPHA);
		}
	}

	/**
	 * "Linie" als flaches Band, das zur Kamera zeigt. Die Breite waechst mit der Entfernung,
	 * sodass die Linie auf dem Bildschirm etwa gleich dick bleibt. Beidseitig gezeichnet (kein Culling-Problem).
	 */
	private static void ribbon(Matrix4fc m, VertexConsumer buf, Vec3 cam, Vec3 p0, Vec3 p1,
							   double k, double minWidth, float r, float g, float b, float a) {
		Vec3 dir = p1.subtract(p0);
		Vec3 toMid = p0.add(p1).scale(0.5).subtract(cam);
		Vec3 side = dir.cross(toMid);
		double len = side.length();

		if (len < 1.0e-9) {
			return;
		}

		side = side.scale(1.0 / len);

		double w0 = Math.max(k * p0.distanceTo(cam), minWidth);
		double w1 = Math.max(k * p1.distanceTo(cam), minWidth);

		Vec3 a0 = p0.add(side.scale(w0));
		Vec3 a1 = p0.subtract(side.scale(w0));
		Vec3 b1 = p1.subtract(side.scale(w1));
		Vec3 b0 = p1.add(side.scale(w1));

		vertex(m, buf, a0, r, g, b, a);
		vertex(m, buf, a1, r, g, b, a);
		vertex(m, buf, b1, r, g, b, a);
		vertex(m, buf, b0, r, g, b, a);

		// Rueckseite
		vertex(m, buf, b0, r, g, b, a);
		vertex(m, buf, b1, r, g, b, a);
		vertex(m, buf, a1, r, g, b, a);
		vertex(m, buf, a0, r, g, b, a);
	}

	private static void vertex(Matrix4fc m, VertexConsumer buf, Vec3 p, float r, float g, float b, float a) {
		buf.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
	}

	private static void filledBox(Matrix4fc m, VertexConsumer buf,
								  double x0, double y0, double z0, double x1, double y1, double z1,
								  float r, float g, float b, float a) {
		float minX = (float) x0, minY = (float) y0, minZ = (float) z0;
		float maxX = (float) x1, maxY = (float) y1, maxZ = (float) z1;

		// Front
		buf.addVertex(m, minX, minY, maxZ).setColor(r, g, b, a);
		buf.addVertex(m, maxX, minY, maxZ).setColor(r, g, b, a);
		buf.addVertex(m, maxX, maxY, maxZ).setColor(r, g, b, a);
		buf.addVertex(m, minX, maxY, maxZ).setColor(r, g, b, a);
		// Back
		buf.addVertex(m, maxX, minY, minZ).setColor(r, g, b, a);
		buf.addVertex(m, minX, minY, minZ).setColor(r, g, b, a);
		buf.addVertex(m, minX, maxY, minZ).setColor(r, g, b, a);
		buf.addVertex(m, maxX, maxY, minZ).setColor(r, g, b, a);
		// Left
		buf.addVertex(m, minX, minY, minZ).setColor(r, g, b, a);
		buf.addVertex(m, minX, minY, maxZ).setColor(r, g, b, a);
		buf.addVertex(m, minX, maxY, maxZ).setColor(r, g, b, a);
		buf.addVertex(m, minX, maxY, minZ).setColor(r, g, b, a);
		// Right
		buf.addVertex(m, maxX, minY, maxZ).setColor(r, g, b, a);
		buf.addVertex(m, maxX, minY, minZ).setColor(r, g, b, a);
		buf.addVertex(m, maxX, maxY, minZ).setColor(r, g, b, a);
		buf.addVertex(m, maxX, maxY, maxZ).setColor(r, g, b, a);
		// Top
		buf.addVertex(m, minX, maxY, maxZ).setColor(r, g, b, a);
		buf.addVertex(m, maxX, maxY, maxZ).setColor(r, g, b, a);
		buf.addVertex(m, maxX, maxY, minZ).setColor(r, g, b, a);
		buf.addVertex(m, minX, maxY, minZ).setColor(r, g, b, a);
		// Bottom
		buf.addVertex(m, minX, minY, minZ).setColor(r, g, b, a);
		buf.addVertex(m, maxX, minY, minZ).setColor(r, g, b, a);
		buf.addVertex(m, maxX, minY, maxZ).setColor(r, g, b, a);
		buf.addVertex(m, minX, minY, maxZ).setColor(r, g, b, a);
	}

	private static void execute(Minecraft client, StagedVertexBuffer.ExecuteInfo info, RenderPipeline pipeline) {
		GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
			.writeTransform(RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

		RenderTarget mainTarget = client.gameRenderer.mainRenderTarget();
		GpuTextureView colorTexture = mainTarget.getColorTextureView();

		assert colorTexture != null;

		try (RenderPass renderPass = RenderSystem.getDevice()
			.createCommandEncoder()
			.createRenderPass(() -> MOD_ID + " render pass", colorTexture, Optional.empty(), mainTarget.getDepthTextureView(), OptionalDouble.empty())) {
			renderPass.setPipeline(pipeline);

			RenderSystem.bindDefaultUniforms(renderPass);
			renderPass.setUniform("DynamicTransforms", dynamicTransforms);

			renderPass.setVertexBuffer(0, info.vertexBuffer().slice());
			renderPass.setIndexBuffer(info.indexBuffer(), info.indexType());
			renderPass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
		}
	}

	/** Unveraenderlicher Render-State (Extraction -> Drawing). */
	private record EspTarget(int x, int y, int z, float r, float g, float b) { }
}
