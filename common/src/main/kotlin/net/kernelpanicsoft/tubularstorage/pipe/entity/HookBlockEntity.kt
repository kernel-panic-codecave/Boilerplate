package net.kernelpanicsoft.tubularstorage.pipe.entity

import dev.architectury.registry.menu.ExtendedMenuProvider
import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.archie.serialization.NestedNBTHolderMap
import net.kernelpanicsoft.archie.serialization.Sync
import net.kernelpanicsoft.archie.serialization.serializers.ResourceLocationSerializer
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.hook.HookHolderState
import net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookState
import net.kernelpanicsoft.tubularstorage.registry.HookTypeRegistry
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
import net.minecraft.Util
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
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
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.PipeHookType] on each face: their [hooks] state
 * (each attachment its own self-contained [HookHolderState], not one shared shape) and the menu
 * that edits them - see `docs/design/m1-pipe-network.md` and `docs/design/m2-sorting-routing.md`.
 */
class HookBlockEntity(pos: BlockPos, state: BlockState) :
	PipeBlockEntity(TileRegistry.Hook, pos, state), ExtendedMenuProvider {

	/**
	 * Keyed by [Direction.name]. The factory resolves which concrete [HookHolderState] subclass to
	 * rebuild for a saved entry from that entry's own raw `type` tag, via [HookTypeRegistry] - not
	 * from any outside context, since the map itself has no per-entry type information beyond what
	 * each entry's own state already carries.
	 */
	@Sync
	val hooks: NestedNBTHolderMap<HookHolderState> by nestedMapField { tag ->
		val id = ResourceLocation.parse(tag.getString("type"))
		HookTypeRegistry.byId(id)?.createState() ?: error("Unknown hook type $id while loading $blockPos")
	}

	/**
	 * Which pipe type this hook block is standing in for, visually - the registry id of a
	 * [net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock] whose own baked model
	 * [net.kernelpanicsoft.tubularstorage.pipe.client.PipeHookBlockEntityRenderer] draws for the
	 * pipe body. Defaults to [NONE] (no pipe placed here yet - see
	 * [net.kernelpanicsoft.tubularstorage.pipe.block.HookBlock]'s KDoc); set to whatever block was
	 * actually placed/promoted, so a future second pipe type (e.g. a glass tier) keeps its own
	 * appearance after promotion instead of all hook blocks looking alike.
	 */
	@Sync
	var pipeBlockId: ResourceLocation by field(ResourceLocationSerializer) { NONE }

	var pendingMenuFace: Direction = Direction.NORTH

	/**
	 * Human-readable progress of the most recently submitted
	 * [net.kernelpanicsoft.tubularstorage.crafting.CraftingJob] on any face - read by
	 * [net.kernelpanicsoft.tubularstorage.pipe.gui.TerminalHookScreen]'s Craft tab via
	 * [net.kernelpanicsoft.archie.gui.blockentity.observeProperty]. Deliberately one flat field
	 * rather than per-face, matching how a player only ever has one terminal screen open at a
	 * time - a second face's own job would overwrite this while the first is still mid-flight, an
	 * accepted simplification for the rare case of two [net.kernelpanicsoft.tubularstorage.pipe.hook.TerminalHookType]
	 * faces on the same block.
	 */
	@Sync
	var craftJobStatus: String by field(String.serializer()) { "" }

	/** The [SortingHookState] filter grid attached to [direction] - callers must already know it carries a sorting hook. */
	fun filterFor(direction: Direction) = (hooks[direction.name] as SortingHookState).filter

	override fun createMenu(id: Int, inventory: Inventory, player: Player): AbstractContainerMenu {
		val hookState = hooks[pendingMenuFace.name] ?: error("No hook at $blockPos/$pendingMenuFace")
		val hookType = HookTypeRegistry.byId(hookState.type) ?: error("Unknown hook type ${hookState.type} at $blockPos/$pendingMenuFace")
		return hookType.createMenu(id, inventory, this, pendingMenuFace)
	}
	override fun getDisplayName(): Component = hooks[pendingMenuFace.name]?.type?.let { Component.translatable(Util.makeDescriptionId("hook", it)) } ?: blockState.block.name
	override fun saveExtraData(buf: FriendlyByteBuf) {
		buf.writeBlockPos(blockPos)
		buf.writeEnum(pendingMenuFace)
	}

	override fun tick(level: Level, pos: BlockPos, state: BlockState) {
		super.tick(level, pos, state)
		if (level.isClientSide) return
		val serverLevel = level as ServerLevel
		if (hooks.size == 0) return
		for ((directionName, hookState) in hooks) {
			val direction = Direction.valueOf(directionName)
			val hookType = HookTypeRegistry.byId(hookState.type) ?: continue
			hookType.tick(serverLevel, pos, direction, this, hookState)
		}
		hooks.touch()
	}

	companion object {
		val NONE: ResourceLocation = TubularStorage.MOD % "none"
		fun tick(level: Level, pos: BlockPos, state: BlockState, tile: HookBlockEntity) = tile.tick(level, pos, state)
	}
}
