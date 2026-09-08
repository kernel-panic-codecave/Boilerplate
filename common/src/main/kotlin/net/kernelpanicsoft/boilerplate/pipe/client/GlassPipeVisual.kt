package net.kernelpanicsoft.boilerplate.pipe.client

import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.task.Plan
import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import dev.engine_room.flywheel.lib.task.SimplePlan
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual
import net.kernelpanicsoft.boilerplate.pipe.entity.GlassPipeBlockEntity
import java.util.function.Consumer

/**
 * Draws what a plain glass pipe is carrying.
 *
 * The pipe's own body is an ordinary block model baked into the chunk mesh - a glass pipe is a real
 * block, unlike a
 * [net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity], whose body and attachments
 * are block-entity geometry ([MultipartBlockEntityVisual] draws those). So the only thing left for
 * this visual is the cargo, which is exactly [TravelingItemInstances].
 *
 * Contents come from [PipeContentsClientCache] rather than the block entity's own list, so the
 * cargo interpolates smoothly between the server's periodic syncs instead of stepping once per
 * packet - the same source the immediate-mode renderer this replaces read from.
 */
class GlassPipeVisual(
	visualizationContext: VisualizationContext,
	blockEntity: GlassPipeBlockEntity,
	partialTick: Float,
) : AbstractBlockEntityVisual<GlassPipeBlockEntity>(visualizationContext, blockEntity, partialTick), DynamicVisual {

	private val cargo = TravelingItemInstances(visualizationContext, pos, visualPos)

	init {
		updateCargo(partialTick)
		updateLight(partialTick)
	}

	private fun updateCargo(partialTick: Float) {
		val level = blockEntity.level ?: return
		val gameTime = level.gameTime + partialTick
		cargo.update(PipeContentsClientCache.get(pos, gameTime.toDouble()), gameTime)
	}

	override fun updateLight(partialTick: Float) {
		cargo.active.forEach { relight(it) }
	}

	override fun _delete() {
		cargo.delete()
	}

	override fun collectCrumblingInstances(consumer: Consumer<Instance?>) {
		cargo.active.forEach(consumer::accept)
	}

	private fun beginFrame(context: DynamicVisual.Context) {
		updateCargo(context.partialTick())
		updateLight(context.partialTick())
	}

	override fun planFrame(): Plan<DynamicVisual.Context> = SimplePlan.of({ context -> beginFrame(context) })
}
