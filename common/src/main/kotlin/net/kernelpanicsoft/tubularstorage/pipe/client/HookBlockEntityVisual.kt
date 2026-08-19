package net.kernelpanicsoft.tubularstorage.pipe.client

import com.mojang.math.Axis
import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.task.Plan
import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.material.Materials
import dev.engine_room.flywheel.lib.model.Models
import dev.engine_room.flywheel.lib.model.baked.BakedModelBuilder
import dev.engine_room.flywheel.lib.task.SimplePlan
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual
import net.kernelpanicsoft.archie.util.plus
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.hook.HookHolderState
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.model.ModelResourceLocation
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.state.BlockState
import org.joml.Quaternionf
import java.util.function.Consumer

/**
 * Renders a [HookBlockEntity]'s pipe body and attached hooks through Flywheel instead of
 * [PipeHookBlockEntityRenderer]'s immediate-mode `tesselateBlock` calls - the point being
 * translucency: a [PipeBlock.isTranslucent] pipe type's body (a hook promoted from
 * [net.kernelpanicsoft.tubularstorage.pipe.block.GlassPipeBlock]) submitted through the ordinary
 * block-entity buffer never gets the same reliable back-to-front sorting a ordinary chunk's own
 * translucent mesh does, which is exactly the kind of instanced-translucency problem Flywheel's
 * own engine is built to handle correctly. [PipeHookBlockEntityRenderer] still runs alongside this
 * (registered with `neverSkipVanillaRender()`) for the one thing Flywheel isn't a good fit for
 * here - the traveling-item overlay, an arbitrary, frequently-changing floating icon per in-flight
 * item, not a good match for an instancing engine built around comparatively stable geometry.
 *
 * [tile.blockState][net.minecraft.world.level.block.entity.BlockEntity.getBlockState] (read live
 * each check, not the copy [AbstractBlockEntityVisual] itself captured at construction) is what
 * actually drives the pipe body's own connection state, and can change at any time a neighboring
 * pipe/inventory appears or disappears - both it and [HookBlockEntity.hooks] are cheap enough to
 * compare every frame that doing so unconditionally, and only actually touching an [Instancer] when
 * something real changed, is simpler than wiring a dedicated invalidation hook for two things that
 * already change this rarely anyway.
 *
 * Every instance is translated by [visualPos] (this block entity's own position relative to
 * Flywheel's current render origin) - unlike the world-space coordinates a `PoseStack`-based
 * renderer works in, an instance's transform starts at the render origin, not the block's own
 * position, so skipping this leaves it rendering at the wrong place entirely rather than merely
 * mispositioned.
 */
class HookBlockEntityVisual(
	visualizationContext: VisualizationContext,
	blockEntity: HookBlockEntity,
	partialTick: Float,
) : AbstractBlockEntityVisual<HookBlockEntity>(visualizationContext, blockEntity, partialTick), DynamicVisual {

	private var pipeInstance: TransformedInstance? = null
	private var lastPipeSignature: Pair<ResourceLocation, BlockState>? = null

	private val hookInstances = HashMap<Direction, TransformedInstance>()
	private var lastHookSignature: Map<Direction, ResourceLocation> = emptyMap()

	init {
		updateInstances()
		updateLight(partialTick)
	}

	override fun update(partialTick: Float) {
		updateInstances()
		updateLight(partialTick)
	}

	private fun updateInstances() {
		updatePipeBody()
		updateHooks()
	}

	private fun updatePipeBody() {
		val pipeBlockId = blockEntity.pipeBlockId
		if (pipeBlockId == HookBlockEntity.NONE) {
			pipeInstance?.delete()
			pipeInstance = null
			lastPipeSignature = null
			return
		}

		val pipeBlock = BuiltInRegistries.BLOCK.get(pipeBlockId) as? PipeBlock ?: BlockRegistry.Pipe
		val pipeState = pipeStateFor(blockEntity, pipeBlock)
		val signature = pipeBlockId to pipeState
		if (pipeInstance == null || signature != lastPipeSignature) {
			pipeInstance?.delete()
			val instancer = instancerProvider().instancer(InstanceTypes.TRANSFORMED, Models.block(pipeState))
			pipeInstance = instancer.createInstance()
			lastPipeSignature = signature
		}
		pipeInstance?.apply {
			setIdentityTransform()
			translate(visualPos.x.toFloat(), visualPos.y.toFloat(), visualPos.z.toFloat())
			setChanged()
		}
	}

	private fun updateHooks() {
		val signature = mutableMapOf<Direction, ResourceLocation>()
		for ((directionName, entry) in blockEntity.hooks) {
			signature[Direction.valueOf(directionName)] = (entry as HookHolderState).type
		}
		if (signature != lastHookSignature) {
			hookInstances.values.forEach(Instance::delete)
			hookInstances.clear()
			for ((direction, hookTypeId) in signature) {
				val hookModel = BakedModelBuilder(Minecraft.getInstance().modelManager.getModel(modelIdFor(hookTypeId)))
					.materialFunc { _, _, _ -> Materials.SOLID_BLOCK }
					.build()
				val instance = instancerProvider().instancer(InstanceTypes.TRANSFORMED, hookModel).createInstance()
				instance.apply {
					setIdentityTransform()
					translate(visualPos.x.toFloat(), visualPos.y.toFloat(), visualPos.z.toFloat())
					translate(0.5f, 0.5f, 0.5f)
					rotate(rotationFor(direction))
					translate(-0.5f, -0.5f, -0.5f)
					setChanged()
				}
				hookInstances[direction] = instance
			}
			lastHookSignature = signature
		}
	}

	override fun updateLight(partialTick: Float) {
		pipeInstance?.let { relight(it) }
		hookInstances.values.forEach { relight(it) }
	}

	override fun _delete() {
		pipeInstance?.delete()
		pipeInstance = null
		hookInstances.values.forEach(Instance::delete)
		hookInstances.clear()
	}

	override fun collectCrumblingInstances(consumer: Consumer<Instance?>) {
		pipeInstance?.let(consumer::accept)
		hookInstances.values.forEach(consumer::accept)
	}

	private fun beginFrame(context: DynamicVisual.Context) {
		updateInstances()
		updateLight(context.partialTick())
	}

	override fun planFrame(): Plan<DynamicVisual.Context> = SimplePlan.of({ context -> beginFrame(context) })

	companion object {
		/**
		 * [pipeBlock]'s own default state, with each direction connected only if [tile] is actually
		 * connected there *and* has no hook attached - an attached hook takes that arm's place rather
		 * than clipping through it, mirroring what a plain (hookless) pipe of that type would show for
		 * the same connections.
		 */
		private fun pipeStateFor(tile: HookBlockEntity, pipeBlock: PipeBlock): BlockState =
			PipeBlock.propertiesByDirection.entries.fold(pipeBlock.defaultBlockState()) { state, (direction, property) ->
				state.setValue(property, tile.blockState.getValue(property) && !tile.hooks.containsKey(direction.name))
			}

		/**
		 * `mymod:extraction` -> the `inventory` variant of `mymod:extraction_hook`'s item model - the
		 * same baked model already guaranteed to exist for that hook's `HookItem` icon (registered as
		 * `<hook type path>_hook`, per [net.kernelpanicsoft.tubularstorage.registry.ItemRegistry]), so
		 * this never queries a model that was never actually baked.
		 */
		private fun modelIdFor(hookTypeId: ResourceLocation): ModelResourceLocation =
			ModelResourceLocation(hookTypeId + "_hook", "inventory")

		/** Each hook's model faces north by default; this rotates it in place to face [direction] instead. */
		private fun rotationFor(direction: Direction): Quaternionf = when (direction) {
			Direction.NORTH -> Axis.YP.rotationDegrees(0f)
			Direction.SOUTH -> Axis.YP.rotationDegrees(180f)
			Direction.EAST -> Axis.YP.rotationDegrees(-90f)
			Direction.WEST -> Axis.YP.rotationDegrees(90f)
			Direction.UP -> Axis.XP.rotationDegrees(90f)
			Direction.DOWN -> Axis.XP.rotationDegrees(-90f)
		}
	}
}
