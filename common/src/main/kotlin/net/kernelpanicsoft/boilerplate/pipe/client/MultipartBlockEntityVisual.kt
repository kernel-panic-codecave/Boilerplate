package net.kernelpanicsoft.boilerplate.pipe.client

import com.mojang.math.Axis
import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.task.Plan
import dev.engine_room.flywheel.api.visual.DynamicVisual
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import dev.engine_room.flywheel.lib.task.SimplePlan
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual
import net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock
import net.kernelpanicsoft.boilerplate.pipe.client.MultipartBlockEntityVisual.Companion.rotationFor
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.HookTypeRegistry
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.joml.Quaternionf
import java.util.function.Consumer

/**
 * Renders a [MultipartBlockEntity]'s pipe body, its attached hooks, and whatever it is currently
 * carrying - everything about the block, since there is no vanilla block entity renderer for any of
 * it any more.
 *
 * Instancing matters most for translucency: a [PipeBlock.isTranslucent] pipe type's body (a hook
 * promoted from [net.kernelpanicsoft.boilerplate.pipe.block.GlassPipeBlock]) submitted through the
 * ordinary block-entity buffer never gets the same reliable back-to-front sorting an ordinary
 * chunk's own translucent mesh does, which is exactly the kind of instanced-translucency problem
 * Flywheel's engine is built to handle correctly. The cargo rides along in [TravelingItemInstances],
 * shared with [GlassPipeVisual].
 *
 * [tile.blockState][net.minecraft.world.level.block.entity.BlockEntity.getBlockState] (read live
 * each check, not the copy [AbstractBlockEntityVisual] itself captured at construction) is what
 * actually drives the pipe body's own connection state, and can change at any time a neighboring
 * pipe/inventory appears or disappears - both it and [MultipartBlockEntity.hooks] are cheap enough to
 * compare every frame that doing so unconditionally, and only actually touching an [Instancer] when
 * something real changed, is simpler than wiring a dedicated invalidation hook for two things that
 * already change this rarely anyway.
 *
 * Every attachment renders as a real [BlockState], not an item model: each hook face bakes its own
 * hook type's [PipeHookType.getRenderState] answer and the casing its encasement type's
 * [net.kernelpanicsoft.boilerplate.pipe.encasement.PipeEncasementType.getRenderState] answer -
 * states of those types' hidden part blocks (see
 * [net.kernelpanicsoft.boilerplate.pipe.block.PartBlock]), whose blockstate definitions select
 * variants, exactly like the pipe body above. The signature comparisons are therefore full-state
 * comparisons: an attachment kind driving variant properties from its own synced holder state gets
 * re-instanced here without any visual-side wiring.
 *
 * Every instance is translated by [visualPos] (this block entity's own position relative to
 * Flywheel's current render origin) - unlike the world-space coordinates a `PoseStack`-based
 * renderer works in, an instance's transform starts at the render origin, not the block's own
 * position, so skipping this leaves it rendering at the wrong place entirely rather than merely
 * mispositioned.
 */
class MultipartBlockEntityVisual(
	visualizationContext: VisualizationContext,
	blockEntity: MultipartBlockEntity,
	partialTick: Float,
) : AbstractBlockEntityVisual<MultipartBlockEntity>(visualizationContext, blockEntity, partialTick), DynamicVisual {

	private var pipeInstance: TransformedInstance? = null
	private var lastPipeSignature: Pair<ResourceLocation, BlockState>? = null

	private val hookInstances = HashMap<Direction, TransformedInstance>()
	private var lastHookSignature: Map<Direction, BlockState> = emptyMap()

	private var encasementInstance: TransformedInstance? = null
	private var lastEncasementSignature: BlockState? = null

	/** What this pipe is currently carrying - see [TravelingItemInstances], shared with [GlassPipeVisual]. */
	private val cargo = TravelingItemInstances(visualizationContext, pos, visualPos)

	init {
		updateInstances()
		updateCargo(partialTick)
		updateLight(partialTick)
	}

	override fun update(partialTick: Float) {
		updateInstances()
		updateCargo(partialTick)
		updateLight(partialTick)
	}

	/**
	 * Contents come from [PipeContentsClientCache] rather than the block entity's own list, so cargo
	 * interpolates smoothly between the server's periodic syncs instead of stepping once per packet.
	 */
	private fun updateCargo(partialTick: Float) {
		val level = blockEntity.level ?: return
		if (!showsTravelingItems()) {
			cargo.delete()
			return
		}
		val gameTime = level.gameTime + partialTick
		cargo.update(PipeContentsClientCache.get(pos, gameTime.toDouble()), gameTime)
	}

	/** Whether this multipart's own promoted pipe type shows what it carries - a solid pipe hides it. */
	private fun showsTravelingItems(): Boolean {
		val pipeBlockId = blockEntity.pipeBlockId
		if (pipeBlockId == MultipartBlockEntity.NONE) return false
		val pipeBlock = BuiltInRegistries.BLOCK.get(pipeBlockId) as? PipeBlock ?: BlockRegistry.Pipe
		return pipeBlock.showsTravelingItems
	}

	private fun updateInstances() {
		updatePipeBody()
		updateEncasement()
		updateHooks()
	}

	/**
	 * The casing model, drawn over the pipe body - its own 10x10x10 housing hides the 6x6x6 core
	 * inside while the connected arms and any hooks still stand proud of it. [signature] is
	 * [PipeEncasementType.getRenderState]'s answer rather than a bare type id, so an encasement
	 * kind driving variant properties from its own state re-instances here for free.
	 */
	private fun updateEncasement() {
		val level = blockEntity.level
		val encasementState = blockEntity.encasement.value
		val encasementType = encasementState?.fromRegistry
		if (level == null || encasementType == null) {
			encasementInstance?.delete()
			encasementInstance = null
			lastEncasementSignature = null
			return
		}
		val signature = encasementType.getRenderState(level, blockEntity.blockPos, lastEncasementSignature ?: AIR_STATE, encasementState)
		if (encasementInstance == null || signature != lastEncasementSignature) {
			encasementInstance?.delete()
			val model = ChunkLitModels.block(signature)
			encasementInstance = instancerProvider().instancer(InstanceTypes.TRANSFORMED, model).createInstance()
			lastEncasementSignature = signature
		}
		encasementInstance?.apply {
			setIdentityTransform()
			translate(visualPos.x.toFloat(), visualPos.y.toFloat(), visualPos.z.toFloat())
			setChanged()
		}
	}

	private fun updatePipeBody() {
		val pipeBlockId = blockEntity.pipeBlockId
		if (pipeBlockId == MultipartBlockEntity.NONE) {
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
			val instancer = instancerProvider().instancer(InstanceTypes.TRANSFORMED, ChunkLitModels.block(pipeState))
			pipeInstance = instancer.createInstance()
			lastPipeSignature = signature
		}
		pipeInstance?.apply {
			setIdentityTransform()
			translate(visualPos.x.toFloat(), visualPos.y.toFloat(), visualPos.z.toFloat())
			setChanged()
		}
	}

	/**
	 * One instance per hooked face, each baked from that face's hook type's own
	 * [PipeHookType.getRenderState] answer - a real BlockState of the type's part block, so a hook
	 * kind driving variant properties from its own state re-instances here for free. Face
	 * orientation stays presentation-level ([rotationFor]); [getRenderState]'s signature carries no
	 * direction on purpose.
	 */
	private fun updateHooks() {
		val level = blockEntity.level ?: return
		val signature = mutableMapOf<Direction, BlockState>()
		for ((directionName, entry) in blockEntity.hooks) {
			val direction = Direction.valueOf(directionName)
			val hookType = HookTypeRegistry.byId(entry.type) ?: continue
			signature[direction] = hookType.getRenderState(level, blockEntity.blockPos, lastHookSignature[direction] ?: AIR_STATE, entry)
		}
		if (signature != lastHookSignature) {
			hookInstances.values.forEach(Instance::delete)
			hookInstances.clear()
			for ((direction, hookState) in signature) {
				val hookModel = ChunkLitModels.block(hookState)
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
		encasementInstance?.let { relight(it) }
		hookInstances.values.forEach { relight(it) }
		cargo.active.forEach { relight(it) }
	}

	override fun _delete() {
		pipeInstance?.delete()
		pipeInstance = null
		encasementInstance?.delete()
		encasementInstance = null
		hookInstances.values.forEach(Instance::delete)
		hookInstances.clear()
		cargo.delete()
	}

	override fun collectCrumblingInstances(consumer: Consumer<Instance?>) {
		pipeInstance?.let(consumer::accept)
		encasementInstance?.let(consumer::accept)
		hookInstances.values.forEach(consumer::accept)
	}

	private fun beginFrame(context: DynamicVisual.Context) {
		updateInstances()
		updateCargo(context.partialTick())
		updateLight(context.partialTick())
	}

	override fun planFrame(): Plan<DynamicVisual.Context> = SimplePlan.of({ context -> beginFrame(context) })

	companion object {
		/** Stand-in "previous state" for a [getRenderState][net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType.getRenderState] call with no cached answer of its own yet - air, so an unknown/unregistered part id also bakes as nothing rather than crashing. */
		private val AIR_STATE = Blocks.AIR.defaultBlockState()

		/**
		 * [pipeBlock]'s own default state, with each direction connected only if [tile] is actually
		 * connected there *and* has no hook attached - an attached hook takes that arm's place rather
		 * than clipping through it, mirroring what a plain (hookless) pipe of that type would show for
		 * the same connections.
		 */
		private fun pipeStateFor(tile: MultipartBlockEntity, pipeBlock: PipeBlock): BlockState =
			PipeBlock.propertiesByDirection.entries.fold(pipeBlock.defaultBlockState()) { state, (direction, property) ->
				state.setValue(property, tile.blockState.getValue(property) && !tile.hooks.containsKey(direction.name))
			}

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
