package net.kernelpanicsoft.boilerplate.pipe.block

import com.mojang.serialization.MapCodec
import dev.architectury.event.EventResult
import dev.architectury.event.events.common.InteractionEvent
import dev.architectury.registry.menu.MenuRegistry
import net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType
import net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock.Companion.removeBarePipe
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.item.EncasementItem
import net.kernelpanicsoft.boilerplate.pipe.item.HookItem
import net.kernelpanicsoft.boilerplate.pipe.item.PipeItem
import net.kernelpanicsoft.boilerplate.pipe.network.PipeNetworkManager
import net.kernelpanicsoft.boilerplate.pipe.network.primaryNetworkTypesAt
import net.kernelpanicsoft.boilerplate.power.network.PressurePipeNetworkManager
import net.kernelpanicsoft.boilerplate.registry.*
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * A pipe segment that can additionally carry attachments: a
 * [net.kernelpanicsoft.boilerplate.pipe.hook.PipeHookType] on each face, and a
 * [net.kernelpanicsoft.boilerplate.pipe.encasement.PipeEncasementType] around the whole segment -
 * see `docs/design/m1-pipe-network.md` and `docs/design/m4-crafting-automation.md`. A plain
 * [PipeBlock] promotes into this the moment a [HookItem] or [EncasementItem] is first used against
 * it (see [PipeBlock.useItemOn]); either item used against air places one of these directly, and it
 * demotes back once its last attachment is gone (see [demoteIfBare]). Kept as its own block/entity
 * pair rather than folded onto every pipe: hooks bring six always-allocated 9-slot filter grids plus
 * a synced map that a plain pipe (the overwhelming majority of a build) shouldn't pay for.
 *
 * Carries no blockstate of its own beyond [propertiesByDirection] (connections): which faces have
 * a hook, and whether the segment is encased, is read straight off
 * [MultipartBlockEntity.hooks]/[MultipartBlockEntity.encasement] rather than mirrored into blockstate -
 * collision (inherited [getShape]) already covers it, and [RenderShape.INVISIBLE] hands *all*
 * visuals (pipe body, hooks and casing alike) to
 * [net.kernelpanicsoft.boilerplate.pipe.client.MultipartBlockEntityVisual], which draws the pipe body
 * as [MultipartBlockEntity.pipeBlockId]'s own baked model rather than a hardcoded one, so a promoted pipe
 * keeps looking like whatever pipe type it actually is.
 */
class MultipartBlock(properties: Properties) : PipeBlock(properties) {
	/**
	 * `null`, so vanilla's spectator interaction never opens this block's menu.
	 *
	 * Spectators *can* open block menus in vanilla - [net.minecraft.server.level.ServerPlayerGameMode.useItemOn]
	 * has an explicit branch that calls `player.openMenu(state.getMenuProvider(...))` before any of
	 * the normal use handling runs, which is how a spectator peeks into a chest. That branch uses
	 * the **plain** `openMenu`, though, and every menu here is an Architectury *extended* menu whose
	 * client-side constructor reads its block position out of a [net.minecraft.network.FriendlyByteBuf].
	 * Opened that way there is no buffer at all, so the client died on
	 * `Cannot invoke "FriendlyByteBuf.readBlockPos()" because "buf" is null` before the screen ever
	 * appeared.
	 *
	 * Returning `null` makes that branch fall through to `PASS`. Normal play is unaffected: this
	 * block opens its own menus through `MenuRegistry.openExtendedMenu` from its use handler, which
	 * never consults this. The only other callers are vanilla's two spectator *crosshair* checks
	 * ([net.minecraft.client.gui.Gui] / [net.minecraft.client.renderer.GameRenderer]), which now
	 * correctly stop advertising a crosshair for a menu a spectator cannot open.
	 */
	override fun getMenuProvider(state: BlockState, level: Level, pos: BlockPos): MenuProvider? = null


	init
	{
		register()
	}

	override fun codec(): MapCodec<out PipeBlock> = CODEC

	override fun getRenderShape(state: BlockState): RenderShape = RenderShape.INVISIBLE

	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity? = TileRegistry.Multipart.create(pos, state)

	/**
	 * Notifies the segment's encasement type that its whole segment is actually gone - the
	 * whole-segment counterpart to the per-attachment detachments in [detachWithWrench], which
	 * cover "player took just the casing/hook off". Guarded to real block
	 * changes (`state.is(newState.block)` stays false across connection-bit-only rewrites), and
	 * deliberately living here rather than in [MultipartBlockEntity.setRemoved]: `setRemoved` also
	 * fires on every chunk unload and world close, where any world access would force-load
	 * neighbors mid-save and stall saving - while [onRemove] only ever runs for gameplay removals,
	 * with the block entity (still present at this point -
	 * `LevelChunk.setBlockState` calls it before removing the BE) readable for its encasement.
	 */
	override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
		if (!state.`is`(newState.block) && level is ServerLevel) {
			val encasementState = (level.getBlockEntity(pos) as? MultipartBlockEntity)?.encasement?.value
			if (encasementState != null) {
				EncasementTypeRegistry.byId(encasementState.type)?.onRemoved(level, pos, encasementState)
			}
		}
		super.onRemove(state, level, pos, newState, movedByPiston)
	}

	override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
		createTickerHelper(type, TileRegistry.Multipart, MultipartBlockEntity::tick)



	/**
	 * Right-clicking a face with a [HookItem] attaches that hook to it (unless that face already
	 * carries one), and an [EncasementItem] wraps the whole segment instead - either only if the
	 * underlying pipe's own [net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock.primaryNetworkTypes]
	 * includes any of the attachment's [net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType.compatibleNetworkTypes]
	 * (a pipe-less segment, with no primary type resolved yet, accepts anything). A [PipeItem]
	 * against a pipe-less segment instead names its own [PipeItem.pipeBlock] - gated the other way
	 * round, against an *already-encased* segment's own `compatibleNetworkTypes`, since there's no
	 * primary type of its own yet to check. Detaching either is the wrench's job - see [register],
	 * which owns that interaction, since vanilla never delivers sneak-clicks carrying an item to
	 * blocks in the first place.
	 */
	override fun useItemOn(
		stack: ItemStack,
		state: BlockState,
		level: Level,
		pos: BlockPos,
		player: Player,
		hand: InteractionHand,
		hitResult: BlockHitResult,
	): ItemInteractionResult = clickBlockWithItem(stack, state, level, pos, player, hand, hitResult)

	fun clickBlockWithItem(
		stack: ItemStack,
		state: BlockState,
		level: Level,
		pos: BlockPos,
		player: Player,
		hand: InteractionHand,
		hitResult: BlockHitResult,
	): ItemInteractionResult {

		val tile = level.getBlockEntity(pos) as? MultipartBlockEntity ?: return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
		val hookItem = stack.item as? HookItem
		val encasementItem = stack.item as? EncasementItem
		val pipeItem = stack.item as? PipeItem
		if (pipeItem != null)
		{
			if (tile.pipeBlockId != MultipartBlockEntity.NONE) return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION
			val encasementType = tile.encasement.value?.fromRegistry
			if (encasementType != null && pipeItem.pipeBlock.primaryNetworkTypes.none { it in encasementType.compatibleNetworkTypes }) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
			if (level.isClientSide) return ItemInteractionResult.SUCCESS
			tile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(pipeItem.pipeBlock)
		}
		else if (encasementItem != null)
		{
			if (level.isClientSide) return ItemInteractionResult.SUCCESS
			if (tile.encasement.value != null) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
			val encasementType = EncasementTypeRegistry.byId(encasementItem.encasementId) ?: return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
			val primaries = primaryNetworkTypesAt(level, pos)
			if (primaries.isNotEmpty() && primaries.none { it in encasementType.compatibleNetworkTypes }) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
			val encasementState = encasementType.createState()
			tile.encasement.value = encasementState
			(level as? ServerLevel)?.let { encasementType.onAttached(it, pos, tile, encasementState) }
		}
		else if (hookItem != null)
		{
			if (level.isClientSide) return ItemInteractionResult.SUCCESS
			val direction = armFor(level, state, pos, hitResult, player) ?: hitResult.direction
			if (tile.hooks.containsKey(direction.name)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
			val hookType = HookTypeRegistry.byId(hookItem.hookId) ?: return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
			val primaries = primaryNetworkTypesAt(level, pos)
			if (primaries.isNotEmpty() && primaries.none { it in hookType.compatibleNetworkTypes }) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
			tile.hooks.getOrPut(direction.name) { hookType.createState() }
			resyncNetworkMembership(level, pos)
		}
		else return if (stack.item is BlockItem) ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION else ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
		level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL)
		state.updateNeighbourShapes(level, pos, Block.UPDATE_ALL)
		level.playSound(null, pos, state.soundType.placeSound, SoundSource.BLOCKS, 1f, 1f)
		if (!player.abilities.instabuild) stack.shrink(1)
		return ItemInteractionResult.SUCCESS
	}

	/**
	 * Sneak-right-clicking with any wrench ([TagsRegistry.Items.TOOLS_WRENCH]) detaches whatever the
	 * hit targets - [armFor]'s face hook, or on such a hit against an encased segment the whole
	 * casing - rolling its type's detach loot table either way. Hitting a part with nothing to
	 * detach there instead (the bare core with no encasement, or a connected arm with no hook)
	 * falls to [removeJustThePipe] - the pipe itself is the one thing left to remove at that spot.
	 * The one removal path that leaves the segment standing; breaking the block takes everything
	 * down with it instead. Reached only through [register]'s right-click listener, and only on the
	 * server - the client passes its own leg through untouched so vanilla's interaction flow stays
	 * intact.
	 */
	internal fun detachWithWrench(
		state: BlockState,
		level: Level,
		pos: BlockPos,
		player: Player,
		hitResult: BlockHitResult,
		tile: MultipartBlockEntity,
	): ItemInteractionResult {
		val arm = armFor(level, state, pos, hitResult, player)
		if (arm == null && tile.encasement.value != null) {
			val encasementState = tile.encasement.value
				?: return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
			val encasementType = EncasementTypeRegistry.byId(encasementState.type)
				?: return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
			if (level.isClientSide) return ItemInteractionResult.SUCCESS
			tile.encasement.value = null
			(level as? ServerLevel)?.let { encasementType.onRemoved(it, pos, encasementState) }
			if (!player.abilities.instabuild && level is ServerLevel) {
				dropDetachLoot(level, pos, state, tile, encasementType, player)
			}
			finishAttachmentBreak(this, level, pos, state, Direction.NORTH)
			return ItemInteractionResult.SUCCESS
		}

		val direction = arm ?: hitResult.direction
		val hookState = tile.hooks[direction.name]
			?: return removeJustThePipe(state, level, pos, player, tile)
		val hookType = HookTypeRegistry.byId(hookState.type)
			?: return removeJustThePipe(state, level, pos, player, tile)
		if (level.isClientSide) return ItemInteractionResult.SUCCESS
		tile.hooks.remove(direction.name)
		resyncNetworkMembership(level, pos)
		if (!player.abilities.instabuild && level is ServerLevel) {
			dropDetachLoot(level, pos, state, tile, hookType, player)
		}
		finishAttachmentBreak(this, level, pos, state, direction)
		return ItemInteractionResult.SUCCESS
	}

	/**
	 * Forces [pos] to be re-evaluated for network membership on both [PipeNetworkManager] and
	 * [PressurePipeNetworkManager] - `onRemoved(pos)` unregisters it (and schedules the rest of its
	 * former network for a rebuild), so the very next tick's own automatic
	 * [net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity.tick] registration call
	 * ([net.kernelpanicsoft.boilerplate.pipe.network.AbstractPipeNetworkManager.ensureRegistered])
	 * re-walks [pos]'s neighbors from scratch rather than being a no-op against its already-known
	 * network id.
	 *
	 * Needed because [AbstractPipeNetworkManager.ensureRegistered] is deliberately idempotent - once
	 * [pos] is registered, it never re-examines its own edges again on its own, so a hook attach/
	 * detach that changes [SubnetBoundary.isBoundaryEdge]/[PressureNetworkBoundary.isBoundaryEdge]'s
	 * result for one of [pos]'s edges (attaching or removing an [InterfaceHookType] or
	 * [AdapterHookType] hook, chiefly) would otherwise never actually take effect until something
	 * *else* forced a rebuild nearby (breaking and replacing an adjacent pipe, say) - confirmed the
	 * hard way: an Adapter hook placed *after* both sides of a pressure-pipe/item-pipe junction were
	 * already registered into separate networks never bridged them, even though
	 * [PressureNetworkBoundary.isBoundaryEdge] itself correctly said the edge was bridged from that
	 * point on - the stale topology just never got told to look again.
	 */
	private fun resyncNetworkMembership(level: Level, pos: BlockPos) {
		val serverLevel = level as? ServerLevel ?: return
		PipeNetworkManager.get(serverLevel).onRemoved(pos)
		PressurePipeNetworkManager.get(serverLevel).onRemoved(pos)
	}

	/**
	 * [detachWithWrench]'s fallback once the hit part carries neither an attached hook nor an
	 * encasement - removes just the pipe this segment stands in for instead: dropping it like a
	 * normal break would (respecting creative mode), then resetting
	 * [MultipartBlockEntity.pipeBlockId] back to [MultipartBlockEntity.NONE], leaving every hook and
	 * the encasement (if any) standing untouched. A no-op
	 * ([ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION]) if there's no pipe here to remove
	 * either (a segment that never got one placed against it).
	 *
	 * The segment's own block/entity never actually goes away here (unlike breaking) - only an
	 * internal field flips - so nothing else notices this position stopped being a pipe on its own;
	 * [net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter.isPipe] reads exactly that field.
	 * Both [PipeNetworkManager] and [PressurePipeNetworkManager] have to be told explicitly, the
	 * same eviction path a real removal would have triggered automatically.
	 */
	private fun removeJustThePipe(state: BlockState, level: Level, pos: BlockPos, player: Player, tile: MultipartBlockEntity): ItemInteractionResult {
		if (tile.pipeBlockId == MultipartBlockEntity.NONE) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
		if (level.isClientSide) return ItemInteractionResult.SUCCESS

		val pipeBlock = BuiltInRegistries.BLOCK.get(tile.pipeBlockId)
		if (!player.abilities.instabuild) {
			dropResources(pipeBlock.defaultBlockState(), level, pos, tile, player, player.mainHandItem)
		}
		tile.pipeBlockId = MultipartBlockEntity.NONE
		resyncNetworkMembership(level, pos)

		level.sendBlockUpdated(pos, state, state, UPDATE_ALL)
		state.updateNeighbourShapes(level, pos, UPDATE_ALL)
		resendSegment(level, pos)
		level.playSound(null, pos, state.soundType.breakSound, SoundSource.BLOCKS, 1f, 1f)
		return ItemInteractionResult.SUCCESS
	}

	/**
	 * Empty-hand right-click opens the targeted part's menu: that face's hook's GUI (if it has one),
	 * or - on a hit landing on no hook and no arm ([armFor] returning `null`) of an encased segment -
	 * the casing's own. Detaching either (or, with nothing to detach, the pipe itself) is the
	 * wrench's job - see [detachWithWrench].
	 */
	override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
		val tile = level.getBlockEntity(pos) as? MultipartBlockEntity ?: return InteractionResult.PASS
		val arm = armFor(level, state, pos, hitResult, player)
		if (arm == null && tile.encasement.value != null) return useEncasement(state, level, pos, player, tile)

		val direction = arm ?: hitResult.direction
		val hookState = tile.hooks[direction.name] ?: return InteractionResult.PASS
		val hookType = HookTypeRegistry.byId(hookState.type) ?: return InteractionResult.PASS

		if (!level.isClientSide && hookType.hasMenu && player is ServerPlayer) {
			tile.pendingMenuFace = direction
			MenuRegistry.openExtendedMenu(player, tile)
		}
		return InteractionResult.PASS
	}

	/** [useWithoutItem]'s casing-hit branch: opens the casing's own menu. */
	private fun useEncasement(state: BlockState, level: Level, pos: BlockPos, player: Player, tile: MultipartBlockEntity): InteractionResult {
		val encasementState = tile.encasement.value ?: return InteractionResult.PASS
		val encasementType = EncasementTypeRegistry.byId(encasementState.type) ?: return InteractionResult.PASS

		if (!level.isClientSide && encasementType.hasMenu && player is ServerPlayer) {
			tile.pendingMenuFace = null
			MenuRegistry.openExtendedMenu(player, tile)
		}
		return InteractionResult.sidedSuccess(level.isClientSide)
	}

	/**
	 * Demotes a segment that has just lost its last attachment back to whichever [PipeBlock]
	 * [MultipartBlockEntity.pipeBlockId] names (transplanting its data across via [CompoundTag]), or to
	 * air if no pipe was ever placed here. Returns the state the position now holds, or `null` if it
	 * still carries a hook or an encasement and so stays promoted. [direction] is the face whose
	 * neighbor the demoted pipe's own shape is recomputed against.
	 */
	internal fun demoteIfBare(level: Level, pos: BlockPos, state: BlockState, direction: Direction): BlockState? {
		val tile = level.getBlockEntity(pos) as? MultipartBlockEntity ?: return null
		if (tile.hooks.iterator().hasNext() || tile.encasement.value != null) return null

		if (tile.pipeBlockId == MultipartBlockEntity.NONE) {
			val air = Blocks.AIR.defaultBlockState()
			level.setBlock(pos, air, UPDATE_ALL)
			return air
		}

		val tag = CompoundTag()
		tile.saveToTag(tag)
		val pipeState = propertiesByDirection.values.fold(
			BuiltInRegistries.BLOCK.get(tile.pipeBlockId).defaultBlockState()
		) { result, property ->
			result.setValue(property, state.getValue(property))
		}.updateShape(direction, level.getBlockState(pos.relative(direction)), level, pos, pos.relative(direction))
		level.setBlock(pos, pipeState, UPDATE_ALL)
		(level.getBlockEntity(pos) as? PipeBlockEntity)?.loadFromTag(tag)
		return pipeState
	}

	override fun spawnDestroyParticles(
		level: Level,
		player: Player,
		pos: BlockPos,
		state: BlockState
	)
	{
		val blockEntity = level.getBlockEntity(pos) as? MultipartBlockEntity ?: return super.spawnDestroyParticles(level, player, pos, state)
		if (blockEntity.pipeBlockId == MultipartBlockEntity.NONE) return super.spawnDestroyParticles(level, player, pos, BlockRegistry.Pipe.defaultBlockState())
		val pipeState = BuiltInRegistries.BLOCK.get(blockEntity.pipeBlockId).defaultBlockState()
		super.spawnDestroyParticles(level, player, pos, pipeState)
	}

	override fun getCloneItemStack(
		level: LevelReader,
		pos: BlockPos,
		state: BlockState
	): ItemStack?
	{
		val tile = level.getBlockEntity(pos) as? MultipartBlockEntity
			?: return super.getCloneItemStack(level, pos, state)

		// Only available / meaningful on the client during pick-block
		if (level is Level && level.isClientSide) {
			val hit = Minecraft.getInstance().hitResult
			if (hit is BlockHitResult && hit.blockPos == pos) {
				val player = Minecraft.getInstance().player
					?: return if (tile.pipeBlockId != MultipartBlockEntity.NONE) BuiltInRegistries.BLOCK.get(tile.pipeBlockId).asItem().defaultInstance
					else super.getCloneItemStack(level, pos, state)
				val direction = armFor(level, state, pos, hit, player)
				val hookState = direction?.let { tile.hooks[it.name] }
				if (hookState != null) {
					val hookType = HookTypeRegistry.byId(hookState.type)
					if (hookType != null) {
						return hookType.asItem().defaultInstance
					}
				}
				// A casing hit resolves to no hook and no arm - see useWithoutItem's own branch.
				if (direction == null) {
					val encasementState = tile.encasement.value
					if (encasementState != null) {
						val encasementType = EncasementTypeRegistry.byId(encasementState.type)
						if (encasementType != null) return encasementType.asItem().defaultInstance
					}
				}
			}
		}

		// Fallback: underlying pipe (or the MultipartBlock itself)
		return if (tile.pipeBlockId != MultipartBlockEntity.NONE) {
			BuiltInRegistries.BLOCK.get(tile.pipeBlockId).asItem().defaultInstance
		} else {
			super.getCloneItemStack(level, pos, state)
		}
	}

	companion object {
		val CODEC: MapCodec<MultipartBlock> = simpleCodec(::MultipartBlock)

		/**
		 * Rolls [type]'s detach loot table (see
		 * [net.kernelpanicsoft.boilerplate.pipe.hook.PipeHookType.detachLootTableId] /
		 * [net.kernelpanicsoft.boilerplate.pipe.encasement.PipeEncasementType.detachLootTableId])
		 * and pops whatever it yields into the world at [pos] - the detachment counterpart of
		 * vanilla's block-drop path, so per-type tables decide whether an attachment survives its
		 * removal at all and with what odds. The context mirrors `LootContextParamSets.BLOCK`,
		 * carrying the tool and the still-live segment block entity alongside the origin, so table
		 * conditions can react to any of them. The table is the sole authority: an id with no table
		 * behind it rolls vanilla's empty placeholder and drops nothing, exactly like a deliberately
		 * empty one.
		 *
		 * Only ever called server-side, after the part has already been removed from [tile].
		 */
		internal fun dropDetachLoot(
			level: ServerLevel,
			pos: BlockPos,
			state: BlockState,
			tile: MultipartBlockEntity,
			type: PipeAttachmentType<*>,
			player: Player,
		) {
			val table = level.server.reloadableRegistries()
				.getLootTable(ResourceKey.create(Registries.LOOT_TABLE, type.detachLootTableId))
			val params = LootParams.Builder(level)
				.withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
				.withParameter(LootContextParams.TOOL, player.mainHandItem)
				.withOptionalParameter(LootContextParams.THIS_ENTITY, player)
				.withOptionalParameter(LootContextParams.BLOCK_STATE, state)
				.withOptionalParameter(LootContextParams.BLOCK_ENTITY, tile)
				.create(LootContextParamSets.BLOCK)
			table.getRandomItems(params) { popResource(level, pos, it) }
		}

		/**
		 * Shared tail of both wrench-detach branches in [MultipartBlock.detachWithWrench]: demote if
		 * that was the last attachment, otherwise just resync/reshape in place, then play the break
		 * sound either way.
		 */
		private fun finishAttachmentBreak(block: MultipartBlock, level: Level, pos: BlockPos, state: BlockState, direction: Direction) {
			if (block.demoteIfBare(level, pos, state, direction) == null) {
				level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL)
				state.updateNeighbourShapes(level, pos, Block.UPDATE_ALL)
				resendSegment(level, pos)
			}
			level.playSound(null, pos, state.soundType.breakSound, SoundSource.BLOCKS, 1f, 1f)
		}

		/**
		 * Repairs the segment on clients whose local copy of the segment just changed underneath them
		 * (a part was detached). The interacting client has *predicted* based on its own pre-click
		 * view, and the ordinary correction traffic can't always reconcile that: a block-update
		 * packet only recreates the block entity blank, while whatever block-entity data was in
		 * flight either landed before that recreation or had nothing to land on. The result is a
		 * segment that stays permanently invisible on that client - casing, hooks
		 * and pipe body are all block-entity reads - even though its neighbors still connect to it.
		 *
		 * Sending a fresh state packet and a fresh block-entity data packet back-to-back, in that
		 * order, directly to everyone tracking [pos] makes the correction self-healing: the first
		 * rebuilds the segment, the second immediately refills it. The later redundant queued packets
		 * from [Level.sendBlockUpdated] then no-op against the already-correct client state.
		 */
		private fun resendSegment(level: Level, pos: BlockPos) {
			if (level !is ServerLevel) return
			val tile = level.getBlockEntity(pos) ?: return
			val statePacket = ClientboundBlockUpdatePacket(level, pos)
			val dataPacket = ClientboundBlockEntityDataPacket.create(tile)
			for (player in level.chunkSource.chunkMap.getPlayers(ChunkPos(pos), false)) {
				player.connection.send(statePacket)
				player.connection.send(dataPacket)
			}
		}
		/**
		 * Wrench detachment lives here rather than in [MultipartBlock.useItemOn] because vanilla
		 * never delivers sneak-clicks carrying an item to blocks at all - both sides skip straight
		 * past block interaction whenever either hand holds something, so no block-side override can
		 * ever see one. Sneak-right-clicking while holding anything tagged `c:tools/wrench` detaches
		 * whatever part of a targeted [MultipartBlock] segment the crosshair resolves to
		 * ([MultipartBlock.detachWithWrench], which itself falls to [MultipartBlock.removeJustThePipe]
		 * once neither a hook nor an encasement is found there) - or, aimed at a bare (unpromoted)
		 * [PipeBlock] instead, removes it outright via [removeBarePipe], the same way breaking it
		 * would, since a bare pipe has nothing else "just the pipe" could mean to leave standing.
		 *
		 * The whole interaction runs server-side only, deliberately: this event fires on the client
		 * too, and consuming it there cancels Fabric's interaction flow before the click packet is
		 * ever sent - the server leg would never run. Passing on the client lets vanilla deliver the
		 * packet as usual; [isSecondaryUseActive] mirrors vanilla's own sneak guard so both sides
		 * agree on when a click belongs to the wrench.
		 */
		fun register() {
			InteractionEvent.RIGHT_CLICK_BLOCK.register { player, hand, _, _ ->
				val level = player.level()
				if (level.isClientSide || hand != InteractionHand.MAIN_HAND || !player.isSecondaryUseActive
					|| !player.mainHandItem.`is`(TagsRegistry.Items.TOOLS_WRENCH))
					return@register EventResult.pass()

				// Retrace fresh rather than trusting the reported position: the server's copy of the
				// player's aim can lag the click by a tick, and hook arms vs casing are small shapes.
				val hit = rayTraceFromPlayer(player) ?: return@register EventResult.pass()
				val pos = hit.blockPos
				val state = level.getBlockState(pos)
				val block = state.block as? PipeBlock ?: return@register EventResult.pass()

				val result = if (block is MultipartBlock) {
					val tile = level.getBlockEntity(pos) as? MultipartBlockEntity ?: return@register EventResult.pass()
					block.detachWithWrench(state, level, pos, player, hit, tile)
				} else {
					removeBarePipe(state, level, pos, player)
				}
				if (result.consumesAction()) EventResult.interruptFalse() else EventResult.pass()
			}
		}

		/**
		 * [register]'s branch for a bare (unpromoted) [PipeBlock]/`GlassPipeBlock` - sneak-right-
		 * clicking it with a wrench drops it like a normal break would (respecting creative mode) and
		 * removes the block, the whole-block counterpart of [MultipartBlock.removeJustThePipe] for a
		 * segment with nothing else on it to leave standing.
		 */
		private fun removeBarePipe(state: BlockState, level: Level, pos: BlockPos, player: Player): ItemInteractionResult {
			if (!player.abilities.instabuild) {
				dropResources(state, level, pos, level.getBlockEntity(pos), player, player.mainHandItem)
			}
			level.removeBlock(pos, false)
			level.playSound(null, pos, state.soundType.breakSound, SoundSource.BLOCKS, 1f, 1f)
			return ItemInteractionResult.SUCCESS
		}

		/**
		 * Re-resolves the crosshair server-side. Vanilla's right-click event carries only the clicked
		 * position and face, while [PipeBlock.armFor] needs the exact entry point - a face-center
		 * guess would misresolve parts aimed near their shared seams.
		 */
		private fun rayTraceFromPlayer(player: Player): BlockHitResult? {
			val eye = player.eyePosition
			val look = player.getViewVector(1f)
			val end = eye.add(look.scale(player.blockInteractionRange()))
			val context = ClipContext(
				eye, end,
				ClipContext.Block.OUTLINE,
				ClipContext.Fluid.NONE,
				player
			)
			val hit = player.level().clip(context)
			return if (hit.type == HitResult.Type.BLOCK) hit else null
		}
	}
}
