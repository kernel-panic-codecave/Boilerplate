package net.kernelpanicsoft.boilerplate.pipe.block

import com.mojang.serialization.MapCodec
import earth.terrarium.common_storage_lib.fluid.FluidApi
import earth.terrarium.common_storage_lib.item.ItemApi
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.item.EncasementItem
import net.kernelpanicsoft.boilerplate.pipe.item.HookItem
import net.kernelpanicsoft.boilerplate.pipe.network.*
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.TileRegistry
import net.kernelpanicsoft.boilerplate.util.byDirection
import net.kernelpanicsoft.boilerplate.util.invoke
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.InteractionHand
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.pathfinder.PathComputationType
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A plain pipe segment: transports items through [PipeBlockEntity], auto-connecting to
 * neighboring pipes and inventories. Carries no hooks - see [MultipartBlock] for the (heavier,
 * hook-carrying) variant this promotes into the moment a [HookItem] is used against it.
 */
open class PipeBlock(properties: Properties) : BaseEntityBlock(properties) {

	/**
	 * Whether this pipe type's contents are visible in transit - see
	 * [net.kernelpanicsoft.boilerplate.pipe.client.TravelingItemInstances]. False for the plain
	 * (opaque) tier; [GlassPipeBlock] overrides it. Consulted by
	 * [net.kernelpanicsoft.boilerplate.pipe.client.MultipartBlockEntityVisual] too, off whatever
	 * pipe type a [MultipartBlock] was promoted from, so a promoted glass pipe keeps showing its
	 * contents and a promoted opaque one doesn't - the trait belongs to the pipe type, not to
	 * whether a hook happens to be attached.
	 */
	open val showsTravelingItems: Boolean = false

	/**
	 * Whether this pipe type's body should render translucent rather than solid. False for the
	 * plain (opaque) tier; [GlassPipeBlock] overrides it. A plain `Boolean`, not a
	 * `net.minecraft.client.renderer.RenderType`, deliberately - that type is client-only, and this
	 * class is loaded on a dedicated server too; [net.kernelpanicsoft.boilerplate.pipe.client.MultipartBlockEntityVisual]
	 * (client-only itself) is what actually maps this to a real `RenderType` for a [MultipartBlock]
	 * promoted from this pipe type, exactly like [showsTravelingItems] above.
	 */
	open val isTranslucent: Boolean = false

	/**
	 * The [NetworkType]s this pipe type is a *primary* carrier of - the set governing which
	 * [net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType] this pipe type may
	 * carry, checked against an attachment's own
	 * [net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType.compatibleNetworkTypes]
	 * at attach time (see [MultipartBlock.clickBlockWithItem]). A pipe can be a primary carrier of
	 * more than one kind at once (a pipe carrying both items and fluids). Derived from the
	 * registered `network_type` set via [registeredPrimaryCarriage] (every registered
	 * [NetworkType.genericPipeCarriage] of [net.kernelpanicsoft.boilerplate.pipe.network.PipeCarriage.PRIMARY] -
	 * Boilerplate's own items and fluids), so an addon registering its gas kind as a primary generic
	 * carrier makes the plain pipe carry it with no per-block edit. [GlassPipeBlock] inherits this
	 * unchanged; [PressurePipeBlock] overrides it to [NetworkTypeRegistry.Pressure]. A plain pipe
	 * carries both items and fluids - one pipe kind serves both networks, routing each envelope
	 * through whichever [net.kernelpanicsoft.boilerplate.pipe.network.ResourceNetworkType] its
	 * resource kind resolves to ([PipeBlockEntity.tick]).
	 */
	open val primaryNetworkTypes: Set<NetworkType> get() = registeredPrimaryCarriage()

	/**
	 * Every other [NetworkType] this pipe type also conducts (registers into, see
	 * [PipeBlockEntity.tick]) without accepting that type's own attachments - a plain item pipe
	 * conducts pressure alongside items, so a dedicated [PressurePipeBlock] run is only needed
	 * where a branch wants pressure with no item transport at all, mirroring
	 * [registeredSecondaryCarriage] for [NetworkType.genericPipeCarriage] of
	 * [net.kernelpanicsoft.boilerplate.pipe.network.PipeCarriage.SECONDARY]. [PressurePipeBlock]
	 * overrides this back to empty (nothing needs an item pipe's own attachments to also flow
	 * through it).
	 */
	open val secondaryNetworkTypes: Set<NetworkType> get() = registeredSecondaryCarriage()

	val allNetworkTypes: Set<NetworkType> get() = primaryNetworkTypes + secondaryNetworkTypes

	/** This pipe type's own core cross-section - see [CORE_SHAPE] for the default every pipe but [PressurePipeBlock] uses. */
	open val coreShape: VoxelShape get() = CORE_SHAPE

	/** This pipe type's own per-direction arm reach - see [ARM_SHAPES] for the default. */
	open val armShapes: Map<Direction, VoxelShape> get() = ARM_SHAPES

	init {
		registerDefaultState(propertiesByDirection.values.fold(stateDefinition.any()) { state, property -> state.setValue(property, false) })
	}

	override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

	override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
		propertiesByDirection.values.forEach { builder.add(it) }
	}

	override fun getStateForPlacement(context: BlockPlaceContext): BlockState
	{
		val state = defaultBlockState()
		if (state.`is`(BlockRegistry.Multipart))
			return state
		return computeConnections(state, context.level, context.clickedPos)
	}

	override fun updateShape(
		state: BlockState,
		direction: Direction,
		neighborState: BlockState,
		level: LevelAccessor,
		pos: BlockPos,
		neighborPos: BlockPos,
	): BlockState = state.setValue(propertiesByDirection.getValue(direction), canConnect(level, pos, direction))

	private fun computeConnections(state: BlockState, level: LevelAccessor, pos: BlockPos): BlockState =
		Direction.entries.fold(state) { result, direction ->
			result.setValue(propertiesByDirection.getValue(direction), canConnect(level, pos, direction))
		}

	/**
	 * A pipe forms a visible connecting arm toward a neighbor sharing any of its own
	 * [primaryNetworkTypes] (an item pipe toward another item-carrying pipe, a pipe carrying both
	 * items and fluids toward another such pipe -
	 * [primaryNetworkTypesAt] resolves a promoted [MultipartBlock] through its own
	 * [MultipartBlockEntity.pipeBlockId]) - deliberately *not* "any neighbor whose full network set
	 * includes mine": an item pipe's own [secondaryNetworkTypes] already carries pressure, but a
	 * dedicated [PressurePipeBlock] butting against it would show a visible cross-section mismatch
	 * (6x6 vs 4x4) without an [net.kernelpanicsoft.boilerplate.pipe.hook.AdapterHookType]
	 * collar - see that hook's own KDoc. Falls back to [externalConnectionExists] for a neighbor
	 * that isn't a pipe at all.
	 *
	 * Resolved via [underlyingPipeBlockAt] rather than reading [primaryNetworkTypes]/
	 * [externalConnectionExists] straight off `this`: `this` is [MultipartBlock] itself for every
	 * promoted segment regardless of which pipe type it was promoted from, so reading those
	 * properties directly would always answer as an item pipe (`MultipartBlock` never overrides
	 * either) - correct by coincidence for a promoted item segment, wrong for a promoted
	 * [PressurePipeBlock] one, which would then neither connect to its own pressure neighbors nor
	 * probe [net.kernelpanicsoft.boilerplate.power.PressureApi] for an external one.
	 *
	 * [adapterBridges] is checked next, ahead of [externalConnectionExists]: an
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.AdapterHookType] hook is what turns an otherwise-
	 * mismatched-primary edge (a dedicated [PressurePipeBlock] butting against an item pipe) into a
	 * genuinely connected one - without this, [net.kernelpanicsoft.boilerplate.power.network.PressureNetworkBoundary]
	 * already merges the two sides' pressure networks at that edge, but the pressure pipe's own
	 * visible arm (and the item pipe's, though a hook already covers that face's own body render
	 * either way) never forms toward it.
	 */
	private fun canConnect(level: LevelAccessor, pos: BlockPos, direction: Direction): Boolean {
		val neighborPos = pos.relative(direction)
		val ownPipeBlock = underlyingPipeBlockAt(level, pos) ?: this
		val tile = level.getBlockEntity(pos) as? MultipartBlockEntity
		if (tile != null && tile.pipeBlockId == MultipartBlockEntity.NONE) return false
		if (primaryNetworkTypesAt(level, neighborPos).any { it in ownPipeBlock.primaryNetworkTypes }) return true
		if (adapterBridges(level, pos, direction)) return true
		val realLevel = level as? Level ?: return false
		return ownPipeBlock.externalConnectionExists(realLevel, neighborPos, direction.opposite)
	}

	/**
	 * Whether a plain (non-pipe) block at [pos] exposes any capability one of this pipe type's own
	 * [allNetworkTypes] reaches - [ItemApi]/[FluidApi] for a plain pipe, plus the pressure lookup it
	 * also conducts; [net.kernelpanicsoft.boilerplate.power.PressureApi] alone for a
	 * [net.kernelpanicsoft.boilerplate.power.block.PressurePipeBlock].
	 *
	 * Asked of each kind's own [NetworkType.externalLookup] rather than named here, so a registered
	 * addon kind connects to its own blocks with no edit to this class - and so a conductor kind
	 * (pressure, which carries no resource and has no [ResourceNetworkType.api]) is not silently
	 * skipped the way a `is ResourceNetworkType` test would skip it.
	 */
	protected open fun externalConnectionExists(level: Level, pos: BlockPos, direction: Direction): Boolean =
		allNetworkTypes.any { it.externalLookup?.find(level, pos, direction) != null }

	/**
	 * The segment's body piece - what remains once hooks and arms are accounted for: a wrapped
	 * segment's whole
	 * [net.kernelpanicsoft.boilerplate.pipe.encasement.PipeEncasementType.casingShape] (including
	 * any protruding face pieces), else the pipe body's own [CORE_SHAPE], or empty when the segment
	 * carries neither an encasement nor a pipe at all.
	 */
	protected fun bodyShapeFor(level: BlockGetter, pos: BlockPos): VoxelShape {
		val tile = level.getBlockEntity(pos) as? MultipartBlockEntity
		val encasementState = tile?.encasement?.value
		encasementState?.fromRegistry?.let { return it.casingShape(level, pos, tile, encasementState) }
		if (tile != null && tile.pipeBlockId == MultipartBlockEntity.NONE) return Shapes.empty()
		return (underlyingPipeBlockAt(level, pos) ?: this).coreShape
	}

	protected fun buildFullShape(state: BlockState, level: BlockGetter, pos: BlockPos): VoxelShape {
		val bodyShape = bodyShapeFor(level, pos)
		return bodyShape {
			val ownArmShapes = (underlyingPipeBlockAt(level, pos) ?: this@PipeBlock).armShapes

			for ((_, hookShape) in attachmentShapes(state, level, pos))
			{
				or(hookShape)
			}

			for ((direction, property) in propertiesByDirection)
			{
				if (state.getValue(property))
				{
					or(ownArmShapes.getValue(direction))
				}
			}
		}
	}

	/** Each face's attachable piece - its hook's own shape if one is attached there, otherwise that face's connected arm. An encasement suppresses the arm fallback: the casing physically covers the arm, so a click there belongs to the casing, not to a piece nothing can interact with. */
	private fun attachmentShapes(state: BlockState, level: BlockGetter, pos: BlockPos): List<Pair<Direction, VoxelShape>> {
		val tile = level.getBlockEntity(pos) as? MultipartBlockEntity ?: return emptyList()

		val shapes = ArrayList<Pair<Direction, VoxelShape>>()
		for ((dirName, hookState) in tile.hooks) {
			val direction = Direction.valueOf(dirName)
			hookState.fromRegistry?.let { shapes.add(direction to it.shapesByDirection.getValue(direction)) }
		}
		if (tile.encasement.value != null) return shapes

		val ownArmShapes = (underlyingPipeBlockAt(level, pos) ?: this).armShapes
		for ((direction, property) in propertiesByDirection) {
			if (state.getValue(property)) shapes.add(direction to ownArmShapes.getValue(direction))
		}
		return shapes
	}

	/**
	 * The segment's full physical geometry, always - core/casing, hooks and arms alike. Deliberately
	 * unconditional: vanilla raytraces against this very shape every frame, so varying it based on
	 * the previous frame's hit result (as an earlier per-part version did) fed the clip back into
	 * itself and made the selection flicker at piece seams. Per-part *outlines* are drawn separately
	 * from [targetedPart] instead - see
	 * [net.kernelpanicsoft.boilerplate.pipe.client.MultipartHighlightRenderer].
	 */
	override fun getShape(
		state: BlockState,
		level: BlockGetter,
		pos: BlockPos,
		context: CollisionContext
	): VoxelShape = buildFullShape(state, level, pos)

	override fun getCollisionShape(
		state: BlockState,
		level: BlockGetter,
		pos: BlockPos,
		context: CollisionContext
	): VoxelShape? = buildFullShape(state, level, pos)

	override fun getOcclusionShape(
		state: BlockState,
		level: BlockGetter,
		pos: BlockPos
	): VoxelShape? = buildFullShape(state, level, pos)

	override fun getBlockSupportShape(
		state: BlockState,
		level: BlockGetter,
		pos: BlockPos
	): VoxelShape? = buildFullShape(state, level, pos)

	/**
	 * Which single part of this segment the ray from [eye] through [hitLocation] entered first: a
	 * [Part.Attachment] (a hook or connected arm on a specific face) or [Part.Body] (the bare pipe
	 * core, or - on a wrapped segment - its whole casing, protruding face pieces included). `null`
	 * only if the ray somehow misses every piece.
	 *
	 * Each candidate is mini-raycast via `VoxelShape.clip` and the nearest entry wins, so the
	 * answer is exact geometry rather than bounding-box containment in a fixed direction order -
	 * grazing hits at seams resolve to the piece the ray actually pierced, and the same hit always
	 * resolves to the same part regardless of what any outline happens to be showing. Runs
	 * identically on both sides; the eye position is passed in rather than derived from
	 * client-only state.
	 */
	fun targetedPart(state: BlockState, level: BlockGetter, pos: BlockPos, eye: Vec3, hitLocation: Vec3): Part? {
		val travel = hitLocation.subtract(eye)
		val end = eye.add(travel.normalize().scale(travel.length() + CLIP_SLACK))

		var bestPart: Part? = null
		var bestDistance = Double.MAX_VALUE

		fun consider(shape: VoxelShape, part: Part) {
			if (shape.isEmpty) return
			val entry = shape.clip(eye, end, pos) ?: return
			val distance = eye.distanceToSqr(entry.location)
			if (distance < bestDistance - EPSILON_SQR) {
				bestDistance = distance
				bestPart = part
			}
		}

		for ((direction, shape) in attachmentShapes(state, level, pos)) {
			consider(shape, Part.Attachment(direction))
		}
		consider(bodyShapeFor(level, pos), Part.Body)

		return bestPart
	}

	/** The outline geometry for [part]: that attachment's own shape, or the body's whole volume - whose boxes decompose into the frame/ring/cap silhouette on an encased segment. */
	fun outlineShapeFor(part: Part, state: BlockState, level: BlockGetter, pos: BlockPos): VoxelShape = when (part) {
		is Part.Attachment -> attachmentShapes(state, level, pos).firstOrNull { it.first == part.direction }?.second ?: Shapes.empty()
		Part.Body -> bodyShapeFor(level, pos)
	}

	/**
	 * Returns the attachment the hit landed in - a hook or connected arm - or null if it landed on
	 * the body. Uses [targetedPart], so the same hit resolves the same way for interaction,
	 * pick-block, breaking and outlining alike.
	 */
	protected open fun armFor(level: Level, state: BlockState, pos: BlockPos, hitResult: BlockHitResult, player: Player): Direction? =
		(targetedPart(state, level, pos, player.eyePosition, hitResult.location) as? Part.Attachment)?.direction

	/**
	 * One part of a segment a hit can resolve to - see [targetedPart].
	 */
	sealed interface Part {
		/** A hook or connected arm attached to one face. */
		data class Attachment(val direction: Direction) : Part

		/** The segment's own body: the bare pipe core, or a wrapped segment's casing. */
		data object Body : Part
	}

	override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity? = TileRegistry.Pipe.create(pos, state)

	override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
		createTickerHelper(type, TileRegistry.Pipe, PipeBlockEntity::tick)

	override fun isPathfindable(state: BlockState, pathComputationType: PathComputationType): Boolean = false

	/**
	 * Right-clicking a plain pipe with a [HookItem] or [EncasementItem] promotes it into a
	 * [MultipartBlock] carrying the same connections, transplanting this block entity's data across via
	 * [CompoundTag] (not vanilla's "with metadata" API, to keep this independent of Archie's own
	 * persistence format), then delegates to [MultipartBlock.useItemOn] to actually attach the
	 * hook/encasement.
	 */
	override fun useItemOn(
		stack: ItemStack,
		state: BlockState,
		level: Level,
		pos: BlockPos,
		player: Player,
		hand: InteractionHand,
		hitResult: BlockHitResult,
	): ItemInteractionResult {
		if (stack.item !is HookItem && stack.item !is EncasementItem) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
		if (level.isClientSide) return ItemInteractionResult.SUCCESS
		val oldTile = level.getBlockEntity(pos) as? PipeBlockEntity ?: return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION

		val tag = CompoundTag()
		oldTile.saveToTag(tag)

		val hookState = propertiesByDirection.values.fold(BlockRegistry.Multipart.defaultBlockState()) { result, property ->
			result.setValue(property, state.getValue(property))
		}
		level.setBlock(pos, hookState, Block.UPDATE_CLIENTS)
		val newTile = level.getBlockEntity(pos) as? MultipartBlockEntity ?: return ItemInteractionResult.SUCCESS
		newTile.loadFromTag(tag)
		newTile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(this)

		return BlockRegistry.Multipart.clickBlockWithItem(stack, level.getBlockState(pos), level, pos, player, hand, hitResult)
	}

	companion object {

		val CODEC: MapCodec<PipeBlock> = simpleCodec(::PipeBlock)

		/** How far past the observed hit the [targetedPart] mini-raycasts run - just enough slack for floating-point error at the surface itself. */
		private const val CLIP_SLACK = 1.0E-3

		/** Tie-breaker margin between candidate entry distances (squared blocks) in [targetedPart], so floating-point noise at shared seams never flips the winner frame to frame - the earlier-considered piece wins instead. */
		private const val EPSILON_SQR = 1.0E-12

		val propertiesByDirection: Map<Direction, BooleanProperty> = mapOf(
			Direction.NORTH to BlockStateProperties.NORTH,
			Direction.SOUTH to BlockStateProperties.SOUTH,
			Direction.EAST to BlockStateProperties.EAST,
			Direction.WEST to BlockStateProperties.WEST,
			Direction.UP to BlockStateProperties.UP,
			Direction.DOWN to BlockStateProperties.DOWN,
		)

		/** 6x6 (`0.3125..0.6875`, pixels 5-11), matching the pipe model's own core cross-section - same numbers as [net.kernelpanicsoft.boilerplate.warehouse.GantryRailBlock.CORE_SHAPE], which uses an identical connecting-block shape. */
		val CORE_SHAPE: VoxelShape = Shapes.box(0.3125, 0.3125, 0.3125, 0.6875, 0.6875, 0.6875)

		/** Same 6x6 cross-section as [CORE_SHAPE], reaching from each face to the core's own boundary - see [net.kernelpanicsoft.boilerplate.warehouse.GantryRailBlock.ARM_SHAPES]. */
		val ARM_SHAPES: Map<Direction, VoxelShape> = Shapes.box(0.3125, 0.3125, 0.0, 0.6875, 0.6875, 0.3125).byDirection
	}
}
