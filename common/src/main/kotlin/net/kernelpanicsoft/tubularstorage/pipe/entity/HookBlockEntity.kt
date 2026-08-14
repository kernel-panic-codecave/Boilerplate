package net.kernelpanicsoft.tubularstorage.pipe.entity

import dev.architectury.registry.menu.ExtendedMenuProvider
import net.kernelpanicsoft.archie.serialization.Sync
import net.kernelpanicsoft.archie.serialization.serializers.ResourceLocationSerializer
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.tubularstorage.pipe.gui.SortingPipeMenu
import net.kernelpanicsoft.tubularstorage.pipe.hook.HookState
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.kernelpanicsoft.tubularstorage.registry.HookTypeRegistry
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState

/**
 * A [PipeBlockEntity] that additionally carries a
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.PipeHookType] on each face: their [hooks] state,
 * their 3x3 filter grids, and the menu that edits them - see `docs/design/m1-pipe-network.md` and
 * `docs/design/m2-sorting-routing.md`.
 */
class HookBlockEntity(pos: BlockPos, state: BlockState) :
	PipeBlockEntity(TileRegistry.Hook, pos, state), ExtendedMenuProvider {

	@Sync
	val hooks by mapField(HookState.serializer()) { emptyMap() }

	/**
	 * Which pipe type this hook block is standing in for, visually - the registry id of a
	 * [net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock] whose own baked model
	 * [net.kernelpanicsoft.tubularstorage.pipe.client.PipeHookBlockEntityRenderer] draws for the
	 * pipe body. Defaults to [BlockRegistry.Pipe]; set to whatever block was actually promoted when
	 * a hook attaches to an existing pipe (see [net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock.useItemOn]),
	 * so a future second pipe type (e.g. a glass tier) keeps its own appearance after promotion
	 * instead of all hook blocks looking alike.
	 */
	var pipeBlockId: ResourceLocation by field(ResourceLocationSerializer) { BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe) }

	val filterNorth by itemField(9)
	val filterSouth by itemField(9)
	val filterEast by itemField(9)
	val filterWest by itemField(9)
	val filterUp by itemField(9)
	val filterDown by itemField(9)

	var pendingMenuFace: Direction = Direction.NORTH

	fun routingFor(direction: Direction): RoutingModule = hooks[direction.name]?.routing ?: RoutingModule()
	fun setRoutingFor(direction: Direction, module: RoutingModule) {
		val state = hooks[direction.name] ?: return
		hooks[direction.name] = state.copy(routing = module)
	}

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

	override fun tick(level: Level, pos: BlockPos, state: BlockState) {
		super.tick(level, pos, state)
		if (level.isClientSide) return
		val serverLevel = level as ServerLevel
		val attached = hooks
		for (directionName in attached.keys.toList()) {
			val hookState = attached[directionName] ?: continue
			val direction = Direction.valueOf(directionName)
			val hookType = HookTypeRegistry.byId(hookState.type) ?: continue
			val next = hookType.tick(serverLevel, pos, direction, this, hookState)
			if (next != hookState) attached[directionName] = next
		}
	}

	companion object {
		fun tick(level: Level, pos: BlockPos, state: BlockState, tile: HookBlockEntity) = tile.tick(level, pos, state)
	}
}
