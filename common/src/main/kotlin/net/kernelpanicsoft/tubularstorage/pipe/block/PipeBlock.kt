package net.kernelpanicsoft.tubularstorage.pipe.block

import com.mojang.serialization.MapCodec
import earth.terrarium.common_storage_lib.item.ItemApi
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.item.EncasementItem
import net.kernelpanicsoft.tubularstorage.pipe.item.HookItem
import net.kernelpanicsoft.tubularstorage.pipe.network.NetworkType
import net.kernelpanicsoft.tubularstorage.pipe.network.adapterBridges
import net.kernelpanicsoft.tubularstorage.pipe.network.primaryNetworkTypeAt
import net.kernelpanicsoft.tubularstorage.pipe.network.underlyingPipeBlockAt
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.kernelpanicsoft.tubularstorage.registry.NetworkTypeRegistry
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
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
	 * [net.kernelpanicsoft.tubularstorage.pipe.client.TravelingItemRenderer]. False for the plain
	 * (opaque) tier; [GlassPipeBlock] overrides it. Consulted by
	 * [net.kernelpanicsoft.tubularstorage.pipe.client.MultipartTravelingItemRenderer] too, off whatever
	 * pipe type a [MultipartBlock] was promoted from, so a promoted glass pipe keeps showing its
	 * contents and a promoted opaque one doesn't - the trait belongs to the pipe type, not to
	 * whether a hook happens to be attached.
	 */
	open val showsTravelingItems: Boolean = false

	/**
	 * Whether this pipe type's body should render translucent rather than solid. False for the
	 * plain (opaque) tier; [GlassPipeBlock] overrides it. A plain `Boolean`, not a
	 * `net.minecraft.client.renderer.RenderType`, deliberately - that type is client-only, and this
	 * class is loaded on a dedicated server too; [net.kernelpanicsoft.tubularstorage.pipe.client.MultipartTravelingItemRenderer]
	 * (client-only itself) is what actually maps this to a real `RenderType` for a [MultipartBlock]
	 * promoted from this pipe type, exactly like [showsTravelingItems] above.
	 */
	open val isTranslucent: Boolean = false

	/**
	 * The one [NetworkType] governing which [net.kernelpanicsoft.tubularstorage.pipe.attachment.PipeAttachmentType]
	 * this pipe type may carry - checked against an attachment's own
	 * [net.kernelpanicsoft.tubularstorage.pipe.attachment.PipeAttachmentType.compatibleNetworkTypes]
	 * at attach time (see [MultipartBlock.clickBlockWithItem]). [GlassPipeBlock] inherits this
	 * unchanged; [PressurePipeBlock] overrides it to [NetworkTypeRegistry.Pressure].
	 */
	open val primaryNetworkType: NetworkType get() = NetworkTypeRegistry.Item

	/**
	 * Every other [NetworkType] this pipe type also conducts (registers into, see
	 * [PipeBlockEntity.tick]) without accepting that type's own attachments - a plain item pipe
	 * conducts pressure alongside items, so a dedicated [PressurePipeBlock] run is only needed
	 * where a branch wants pressure with no item transport at all. [PressurePipeBlock] overrides
	 * this back to empty (nothing needs an item pipe's own attachments to also flow through it).
	 */
	open val secondaryNetworkTypes: Set<NetworkType> get() = setOf(NetworkTypeRegistry.Pressure)

	/** This pipe type's own core cross-section - see [CORE_SHAPE] for the default every pipe but [PressurePipeBlock] uses. */
	open val coreShape: VoxelShape get() = CORE_SHAPE

	/** This pipe type's own per-direction arm reach - see [armShapes] for the default. */
	open val armShapesByDirection: Map<Direction, VoxelShape> get() = armShapes

	init {
		registerDefaultState(propertiesByDirection.values.fold(stateDefinition.any()) { state, property -> state.setValue(property, false) })
	}

	override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

	override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
		propertiesByDirection.values.forEach { builder.add(it) }
	}

	override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
		computeConnections(defaultBlockState(), context.level, context.clickedPos)

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
	 * A pipe forms a visible connecting arm toward a neighbor sharing its own [primaryNetworkType]
	 * (an item pipe toward another item pipe, a pressure pipe toward another pressure pipe -
	 * [primaryNetworkTypeAt] resolves a promoted [MultipartBlock] through its own
	 * [MultipartBlockEntity.pipeBlockId]) - deliberately *not* "any neighbor whose full network set
	 * includes mine": an item pipe's own [secondaryNetworkTypes] already carries pressure, but a
	 * dedicated [PressurePipeBlock] butting against it would show a visible cross-section mismatch
	 * (6x6 vs 4x4) without an [net.kernelpanicsoft.tubularstorage.pipe.hook.AdapterHookType]
	 * collar - see that hook's own KDoc. Falls back to [externalConnectionExists] for a neighbor
	 * that isn't a pipe at all.
	 *
	 * Resolved via [underlyingPipeBlockAt] rather than reading [primaryNetworkType]/
	 * [externalConnectionExists] straight off `this`: `this` is [MultipartBlock] itself for every
	 * promoted segment regardless of which pipe type it was promoted from, so reading those
	 * properties directly would always answer as an item pipe (`MultipartBlock` never overrides
	 * either) - correct by coincidence for a promoted item segment, wrong for a promoted
	 * [PressurePipeBlock] one, which would then neither connect to its own pressure neighbors nor
	 * probe [net.kernelpanicsoft.tubularstorage.power.PressureApi] for an external one.
	 *
	 * [adapterBridges] is checked next, ahead of [externalConnectionExists]: an
	 * [net.kernelpanicsoft.tubularstorage.pipe.hook.AdapterHookType] hook is what turns an otherwise-
	 * mismatched-primary edge (a dedicated [PressurePipeBlock] butting against an item pipe) into a
	 * genuinely connected one - without this, [net.kernelpanicsoft.tubularstorage.power.network.PressureNetworkBoundary]
	 * already merges the two sides' pressure networks at that edge, but the pressure pipe's own
	 * visible arm (and the item pipe's, though a hook already covers that face's own body render
	 * either way) never forms toward it.
	 */
	private fun canConnect(level: LevelAccessor, pos: BlockPos, direction: Direction): Boolean {
		val neighborPos = pos.relative(direction)
		val ownPipeBlock = underlyingPipeBlockAt(level, pos) ?: this
		if (primaryNetworkTypeAt(level, neighborPos) == ownPipeBlock.primaryNetworkType) return true
		if (adapterBridges(level, pos, direction)) return true
		val realLevel = level as? Level ?: return false
		return ownPipeBlock.externalConnectionExists(realLevel, neighborPos, direction.opposite)
	}

	/** The external (non-pipe) capability this pipe type auto-connects to - [ItemApi] for a plain item pipe; [PressurePipeBlock] overrides this to [net.kernelpanicsoft.tubularstorage.power.PressureApi] instead. */
	protected open fun externalConnectionExists(level: Level, pos: BlockPos, direction: Direction): Boolean =
		ItemApi.BLOCK.find(level, pos, direction) != null

	/**
	 * The segment's body piece - what remains once hooks and arms are accounted for: a wrapped
	 * segment's whole
	 * [net.kernelpanicsoft.tubularstorage.pipe.encasement.PipeEncasementType.casingShape] (including
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
		var shape = bodyShapeFor(level, pos)
		val ownArmShapes = (underlyingPipeBlockAt(level, pos) ?: this).armShapesByDirection

		for ((direction, hookShape) in attachmentShapes(state, level, pos)) {
			shape = Shapes.or(shape, hookShape)
		}

		for ((direction, property) in propertiesByDirection) {
			if (state.getValue(property)) {
				shape = Shapes.or(shape, ownArmShapes.getValue(direction))
			}
		}

		return shape
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

		val ownArmShapes = (underlyingPipeBlockAt(level, pos) ?: this).armShapesByDirection
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
	 * [net.kernelpanicsoft.tubularstorage.pipe.client.MultipartHighlightRenderer].
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

		/** 6x6 (`0.3125..0.6875`, pixels 5-11), matching the pipe model's own core cross-section - same numbers as [net.kernelpanicsoft.tubularstorage.warehouse.GantryRailBlock.CORE_SHAPE], which uses an identical connecting-block shape. */
		val CORE_SHAPE: VoxelShape = Shapes.box(0.3125, 0.3125, 0.3125, 0.6875, 0.6875, 0.6875)

		/** Same 6x6 cross-section as [CORE_SHAPE], reaching from each face to the core's own boundary - see [net.kernelpanicsoft.tubularstorage.warehouse.GantryRailBlock.armShapes]. */
		val armShapes: Map<Direction, VoxelShape> = mapOf(
			Direction.NORTH to Shapes.box(0.3125, 0.3125, 0.0, 0.6875, 0.6875, 0.3125),
			Direction.SOUTH to Shapes.box(0.3125, 0.3125, 0.6875, 0.6875, 0.6875, 1.0),
			Direction.WEST to Shapes.box(0.0, 0.3125, 0.3125, 0.3125, 0.6875, 0.6875),
			Direction.EAST to Shapes.box(0.6875, 0.3125, 0.3125, 1.0, 0.6875, 0.6875),
			Direction.DOWN to Shapes.box(0.3125, 0.0, 0.3125, 0.6875, 0.3125, 0.6875),
			Direction.UP to Shapes.box(0.3125, 0.6875, 0.3125, 0.6875, 1.0, 0.6875),
		)
	}
}
