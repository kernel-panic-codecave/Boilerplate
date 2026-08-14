package net.kernelpanicsoft.tubularstorage.pipe.entity

import dev.architectury.registry.menu.ExtendedMenuProvider
import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.block.entity.NBTBlockEntity
import net.kernelpanicsoft.archie.serialization.Sync
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.tubularstorage.network.PipeContentsSyncPacket
import net.kernelpanicsoft.tubularstorage.network.TubularStorageNetworkChannel
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock
import net.kernelpanicsoft.tubularstorage.pipe.client.PipeContentsClientCache
import net.kernelpanicsoft.tubularstorage.pipe.gui.SortingPipeMenu
import net.kernelpanicsoft.tubularstorage.pipe.hook.HookState
import net.kernelpanicsoft.tubularstorage.pipe.network.PipeNetworkManager
import net.kernelpanicsoft.tubularstorage.power.PressureConsumer
import net.kernelpanicsoft.tubularstorage.registry.HookTypeRegistry
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

/**
 * A pipe segment. Holds and advances [TravelingItem]s in transit, and carries zero or more
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.PipeHookType] attachments - one per face, keyed by
 * [Direction.name] in [hooks] - each ticked every server tick. Doubles as
 * [SortingPipeMenu]'s menu provider for whichever face's hook last opened it - see [PipeBlock].
 */
open class PipeBlockEntity(type: BlockEntityType<*>, pos: BlockPos, state: BlockState) :
	NBTBlockEntity(type, pos, state), PressureConsumer, ExtendedMenuProvider {

	constructor(pos: BlockPos, state: BlockState) : this(TileRegistry.Pipe, pos, state)

	val travelingItems by listField(TravelingItem.serializer()) { emptyList() }

	/**
	 * Which [net.kernelpanicsoft.tubularstorage.pipe.hook.PipeHookType] (if any) is attached to
	 * each face, keyed by [Direction.name]. `@Sync`ed - not for live GUI observation (nothing
	 * observes it that way), but because [net.kernelpanicsoft.archie.serialization.NBTHolder]'s
	 * network update tag only ever includes `@Sync`-marked fields; without it, a client's own copy
	 * of this field (and hence [net.kernelpanicsoft.tubularstorage.pipe.client.PipeHookBlockEntityRenderer]'s
	 * view of it) never receives attach/remove changes at all, no matter how they're pushed.
	 * Structural changes alone (`setChanged()`, called automatically by the underlying field) only
	 * mark the chunk dirty for saving - they never push a network resync, so attach/remove sites
	 * ([net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock.useItemOn]/[useWithoutItem])
	 * additionally call `level.sendBlockUpdated(...)` themselves to actually trigger one.
	 */
	@Sync
	val hooks by mapField(HookState.serializer()) { emptyMap() }

	val filterNorth by itemField(9)
	val filterSouth by itemField(9)
	val filterEast by itemField(9)
	val filterWest by itemField(9)
	val filterUp by itemField(9)
	val filterDown by itemField(9)

	/** The face last targeted by a menu-opening interaction - not persisted, only meaningful for the duration of [createMenu]/[saveExtraData]. */
	var pendingMenuFace: Direction = Direction.NORTH

	private var ticksSinceSync = 0

	/** [HookState.routing] for [direction]'s hook, consulted by a sorting hook (mode/priority/color) or an extraction hook (`color` only) - see [net.kernelpanicsoft.tubularstorage.pipe.network.PipeRouter]. Defaults for a face with no hook attached. */
	fun routingFor(direction: Direction): RoutingModule = hooks[direction.name]?.routing ?: RoutingModule()

	/** Updates [direction]'s hook's [HookState.routing] in place - only meaningful for a face that already has a hook attached. */
	fun setRoutingFor(direction: Direction, module: RoutingModule) {
		val state = hooks[direction.name] ?: return
		hooks[direction.name] = state.copy(routing = module)
	}

	/** The 3x3 filter grid a sorting hook on [direction] consults - see [net.kernelpanicsoft.tubularstorage.pipe.network.PipeRouter]. */
	fun filterFor(direction: Direction): ArchieItemStorage = when (direction) {
		Direction.NORTH -> filterNorth
		Direction.SOUTH -> filterSouth
		Direction.EAST -> filterEast
		Direction.WEST -> filterWest
		Direction.UP -> filterUp
		Direction.DOWN -> filterDown
	}

	override fun createMenu(id: Int, inventory: Inventory, player: Player): AbstractContainerMenu = SortingPipeMenu(id, inventory, this, pendingMenuFace)

	override fun getDisplayName(): Component = blockState.block.name

	override fun saveExtraData(buf: FriendlyByteBuf) {
		buf.writeBlockPos(blockPos)
		buf.writeEnum(pendingMenuFace)
	}

	override fun setRemoved() {
		super.setRemoved()
		val serverLevel = level as? ServerLevel ?: return
		PipeNetworkManager.get(serverLevel).onRemoved(blockPos)
		PipeContentsClientCache.remove(blockPos)
	}

	/**
	 * Archie's [listField]/[mapField] decode a fresh collection from storage on every property
	 * access; only the structural operations [net.kernelpanicsoft.archie.serialization.ObservableList]/
	 * [net.kernelpanicsoft.archie.serialization.ObservableMap] actually intercept (`add`/`removeAt`/
	 * `set`/`clear`/`put`, ...) persist. [travelingItems] and [hooks] are each fetched exactly once
	 * here and touched only through index-/key-based mutation, with [TravelingItem.copy]/
	 * [HookState.copy] standing in for field mutation - in-place mutation of an element already in
	 * the collection, or removal via an `Iterator`, silently affects only a throwaway copy.
	 */
	open fun tick(level: Level, pos: BlockPos, state: BlockState) {
		if (level.isClientSide) return
		val serverLevel = level as ServerLevel
		// Idempotent - this is also how a pipe re-registers after a chunk (re)load, there being no
		// dedicated "block entity now active" hook to call it from instead.
		PipeNetworkManager.get(serverLevel).ensureRegistered(serverLevel, pos)
		var hopped = false

		val items = travelingItems
		var index = 0
		while (index < items.size) {
			val item = items[index]
			val progress = item.progress + SEGMENT_SPEED
			if (progress < 1f) {
				items[index] = item.copy(progress = progress)
				index++
				continue
			}

			val nextPos = item.path.firstOrNull()
			if (nextPos == null) {
				jam(serverLevel, pos, item)
				items.removeAt(index)
				hopped = true
				continue
			}

			if (isPipe(serverLevel, nextPos)) {
				val nextTile = serverLevel.getBlockEntity(nextPos) as? PipeBlockEntity
				if (nextTile == null) {
					jam(serverLevel, pos, item)
					items.removeAt(index)
					hopped = true
					continue
				}
				val direction = Direction.fromDelta(nextPos.x - pos.x, nextPos.y - pos.y, nextPos.z - pos.z)
				nextTile.travelingItems += TravelingItem(item.stack, direction?.opposite ?: item.fromDirection, 0f, item.path.drop(1), item.color)
				items.removeAt(index)
				hopped = true
				continue
			}

			val direction = Direction.fromDelta(nextPos.x - pos.x, nextPos.y - pos.y, nextPos.z - pos.z)
			val storage = ItemApi.BLOCK.find(serverLevel, nextPos, direction?.opposite)
			if (storage == null) {
				jam(serverLevel, pos, item)
				items.removeAt(index)
				hopped = true
				continue
			}

			val resource = ItemResource.of(item.stack)
			val inserted = storage.insert(resource, item.stack.count.toLong(), false)
			when {
				inserted >= item.stack.count -> {
					items.removeAt(index)
					hopped = true
				}
				inserted > 0 -> {
					item.stack.shrink(inserted.toInt())
					items[index] = item.copy(progress = 1f)
					hopped = true
					index++
				}
				else -> {
					items[index] = item.copy(progress = 1f) // stall, retry next tick
					index++
				}
			}
		}

		val attached = hooks
		for (directionName in attached.keys.toList()) {
			val hookState = attached[directionName] ?: continue
			val direction = Direction.valueOf(directionName)
			val hookType = HookTypeRegistry.byId(hookState.type) ?: continue
			val next = hookType.tick(serverLevel, pos, direction, this, hookState)
			if (next != hookState) attached[directionName] = next
		}

		ticksSinceSync++
		if (hopped || (items.isNotEmpty() && ticksSinceSync >= SYNC_INTERVAL_TICKS)) {
			ticksSinceSync = 0
			syncToNearbyPlayers(serverLevel, pos, items)
		}
	}

	private fun jam(level: ServerLevel, pos: BlockPos, item: TravelingItem) {
		ItemEntity(level, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, item.stack)
			.also { level.addFreshEntity(it) }
	}

	private fun syncToNearbyPlayers(level: ServerLevel, pos: BlockPos, items: List<TravelingItem>) {
		TubularStorageNetworkChannel.toNearPlayers(
			level, null, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, SYNC_RADIUS,
			PipeContentsSyncPacket(pos, items.toList()),
		)
	}

	protected fun isPipe(level: Level, pos: BlockPos): Boolean = level.getBlockState(pos).block is PipeBlock

	companion object {
		/** Progress gained per tick; 1f / SEGMENT_SPEED ticks to cross one pipe segment. */
		const val SEGMENT_SPEED = 1f / 20f
		const val SYNC_INTERVAL_TICKS = 4
		const val SYNC_RADIUS = 32.0

		fun tick(level: Level, pos: BlockPos, state: BlockState, tile: PipeBlockEntity) = tile.tick(level, pos, state)
	}
}
