package net.kernelpanicsoft.tubularstorage.pipe.entity

import dev.architectury.registry.menu.ExtendedMenuProvider
import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.archie.serialization.NestedNBTHolder
import net.kernelpanicsoft.archie.serialization.NestedNBTHolderMap
import net.kernelpanicsoft.archie.serialization.Sync
import net.kernelpanicsoft.archie.serialization.serializers.ResourceLocationSerializer
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.encasement.EncasementHolderState
import net.kernelpanicsoft.tubularstorage.pipe.hook.HookHolderState
import net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookState
import net.kernelpanicsoft.tubularstorage.registry.EncasementTypeRegistry
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
 * A [PipeBlockEntity] that additionally carries attachments: a
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.PipeHookType] on each face ([hooks], each its own
 * self-contained [HookHolderState] rather than one shared shape) and a
 * [PipeEncasementType][net.kernelpanicsoft.tubularstorage.pipe.encasement.PipeEncasementType]
 * around the whole segment ([encasement]), plus the menus that edit them - see
 * `docs/design/m1-pipe-network.md`, `docs/design/m2-sorting-routing.md` and
 * `docs/design/m4-crafting-automation.md`. Hooks and an encasement coexist freely; either one alone
 * is enough to keep a segment promoted to this block rather than demoted back to a plain pipe.
 */
class MultipartBlockEntity(pos: BlockPos, state: BlockState) :
	PipeBlockEntity(TileRegistry.Multipart, pos, state), ExtendedMenuProvider {

	/**
	 * Keyed by [Direction.name]. The factory resolves which concrete [HookHolderState] subclass to
	 * rebuild for a saved entry from that entry's own raw `type` tag, via [HookTypeRegistry] - not
	 * from any outside context, since the map itself has no per-entry type information beyond what
	 * each entry's own state already carries.
	 */
	@Sync
	val hooks: NestedNBTHolderMap<HookHolderState> by nestedMapField { tag ->
		val id = ResourceLocation.parse(tag.getString("type"))
		HookTypeRegistry.byId(id)?.createState()
	}

	/** The encasement wrapping this whole segment, or `null` if it carries none. */
	@Sync
	val encasement: NestedNBTHolder<EncasementHolderState> by nestedField { tag ->
		val id = ResourceLocation.parse(tag.getString("type"))
		EncasementTypeRegistry.byId(id)?.createState()
	}

	/**
	 * Which pipe type this hook block is standing in for, visually - the registry id of a
	 * [net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock] whose own baked model
	 * [net.kernelpanicsoft.tubularstorage.pipe.client.MultipartTravelingItemRenderer] draws for the
	 * pipe body. Defaults to [NONE] (no pipe placed here yet - see
	 * [net.kernelpanicsoft.tubularstorage.pipe.block.MultipartBlock]'s KDoc); set to whatever block was
	 * actually placed/promoted, so a future second pipe type (e.g. a glass tier) keeps its own
	 * appearance after promotion instead of all hook blocks looking alike.
	 */
	@Sync
	var pipeBlockId: ResourceLocation by field(ResourceLocationSerializer) { NONE }

	/** Which attachment the next [createMenu] is for: a hooked face, or `null` for [encasement]. */
	var pendingMenuFace: Direction? = null

	/**
	 * Human-readable progress of the most recently submitted
	 * [net.kernelpanicsoft.tubularstorage.crafting.CraftingBufferJob] on any face - read by
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
		val face = pendingMenuFace ?: run {
			val encasementState = encasement.value ?: error("No encasement at $blockPos")
			val encasementType = encasementState.fromRegistry ?: error("Unknown encasement type ${encasementState.type} at $blockPos")
			return encasementType.createMenu(id, inventory, this)
		}
		val hookState = hooks[face.name] ?: error("No hook at $blockPos/$face")
		val hookType = hookState.fromRegistry ?: error("Unknown hook type ${hookState.type} at $blockPos/$face")
		return hookType.createMenu(id, inventory, this, face)
	}
	override fun getDisplayName(): Component {
		val face = pendingMenuFace
			?: return encasement.value?.type?.let { Component.translatable(Util.makeDescriptionId("encasement", it)) } ?: blockState.block.name
		return hooks[face.name]?.type?.let { Component.translatable(Util.makeDescriptionId("hook", it)) } ?: blockState.block.name
	}

	/** The encasement's own menu carries no face, so it gets the bare position - see each reader in [net.kernelpanicsoft.tubularstorage.registry.GuiRegistry]. */
	override fun saveExtraData(buf: FriendlyByteBuf) {
		buf.writeBlockPos(blockPos)
		pendingMenuFace?.let { buf.writeEnum(it) }
	}

	private var firstTick = true

	override fun tick(level: Level, pos: BlockPos, state: BlockState) {
		super.tick(level, pos, state)
		if (level.isClientSide) return
		val serverLevel = level as ServerLevel
		if (hooks.size > 0) {
			for ((directionName, hookState) in hooks) {
				val direction = Direction.valueOf(directionName)
				val hookType = hookState.fromRegistry ?: continue
				if (firstTick) hookType.start(serverLevel, pos, direction, this, hookState)
				hookType.tick(serverLevel, pos, direction, this, hookState)
			}
			hooks.touch()
		}
		val encasementState = encasement.value ?: return
		if (firstTick) encasementState.fromRegistry?.start(serverLevel, pos, this, encasementState)
		encasementState.fromRegistry?.tick(serverLevel, pos, this, encasementState)
		encasement.touch()
		if (firstTick) firstTick = false
	}

	override fun setRemoved() {
		super.setRemoved()
		val serverLevel = level as? ServerLevel ?: return
		for ((directionName, hookState) in hooks) hookState.fromRegistry?.end(serverLevel, blockPos, Direction.valueOf(directionName), this, hookState)
		encasement.value?.let { it.fromRegistry?.end(serverLevel, blockPos, this, it) }
	}

	companion object {
		val NONE: ResourceLocation = TubularStorage.MOD % "none"

		fun tick(level: Level, pos: BlockPos, state: BlockState, tile: MultipartBlockEntity) = tile.tick(level, pos, state)
	}
}
