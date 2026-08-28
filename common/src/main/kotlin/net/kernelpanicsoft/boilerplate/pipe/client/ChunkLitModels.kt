package net.kernelpanicsoft.boilerplate.pipe.client

import dev.engine_room.flywheel.api.material.CardinalLightingMode
import dev.engine_room.flywheel.api.material.Material
import dev.engine_room.flywheel.api.model.Model
import dev.engine_room.flywheel.lib.material.SimpleMaterial
import dev.engine_room.flywheel.lib.model.ModelUtil
import dev.engine_room.flywheel.lib.model.baked.BlockModelBuilder
import dev.engine_room.flywheel.lib.model.baked.SinglePosVirtualBlockGetter
import dev.engine_room.flywheel.lib.util.RendererReloadCache
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import java.util.concurrent.ConcurrentHashMap

/**
 * Flywheel [Model]s for block-shaped pipe-segment geometry - pipe bodies, hooks and encasements -
 * lit the way chunk geometry is.
 *
 * Every entry in Flywheel's own [dev.engine_room.flywheel.lib.material.Materials] takes the default
 * [CardinalLightingMode.ENTITY], whose diffuse factor follows `RenderSystem`'s entity light
 * directions rather than vanilla's per-face block shading. On axis-aligned geometry that reads as
 * too dark on every face but the top: 0.50 east/west and 0.74 north/south against vanilla's 0.60
 * and 0.80, and 0.40 down against 0.50. [CardinalLightingMode.CHUNK] reproduces vanilla's factors
 * exactly, so a casing or hook sitting flush against real blocks matches them.
 *
 * The model cache is keyed to Flywheel's own renderer-reload lifecycle, so a resource reload
 * rebakes it and a repeated lookup reuses one [Model] - and so one
 * [Instancer][dev.engine_room.flywheel.api.instance.Instancer] - per key.
 */
object ChunkLitModels {
	private val chunkLitMaterials = ConcurrentHashMap<Material, Material>()

	private val blockStateModels = RendererReloadCache<BlockState, Model> { state ->
		BlockModelBuilder(SinglePosVirtualBlockGetter.createFullDark().blockState(state), listOf(BlockPos.ZERO))
			.materialFunc { renderType, shaded, ambientOcclusion -> chunkLit(renderType, shaded, ambientOcclusion) }
			.build()
	}

	/** [state]'s own block model, as [dev.engine_room.flywheel.lib.model.Models.block] builds it but chunk-lit. */
	fun block(state: BlockState): Model = blockStateModels.get(state)

	/** [ModelUtil.getMaterial]'s answer for a quad, with entity-direction shading swapped for chunk shading; a quad Flywheel already resolves to an unshaded material stays unshaded. */
	private fun chunkLit(renderType: RenderType, shaded: Boolean, ambientOcclusion: Boolean): Material? =
		ModelUtil.getMaterial(renderType, shaded, ambientOcclusion)?.let { base ->
			if (base.cardinalLightingMode() != CardinalLightingMode.ENTITY) base
			else chunkLitMaterials.computeIfAbsent(base) {
				SimpleMaterial.builderOf(it).cardinalLightingMode(CardinalLightingMode.CHUNK).build()
			}
		}
}
