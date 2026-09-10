package net.kernelpanicsoft.boilerplate.pipe.entity

import dev.architectury.registry.menu.ExtendedMenuProvider
import net.kernelpanicsoft.archie.serialization.NestedNBTHolder
import net.kernelpanicsoft.archie.serialization.NestedNBTHolderMap
import net.kernelpanicsoft.archie.serialization.Sync
import net.kernelpanicsoft.archie.serialization.serializers.ResourceLocationSerializer
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.pipe.encasement.EncasementHolderState
import net.kernelpanicsoft.boilerplate.pipe.hook.BoundaryRelay
import net.kernelpanicsoft.boilerplate.pipe.hook.HookHolderState
import net.kernelpanicsoft.boilerplate.pipe.hook.PipeHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState
import net.kernelpanicsoft.boilerplate.power.PressureConsumer
import net.kernelpanicsoft.boilerplate.power.PressureLine
import net.kernelpanicsoft.boilerplate.registry.EncasementTypeRegistry
import net.kernelpanicsoft.boilerplate.registry.HookTypeRegistry
import net.kernelpanicsoft.boilerplate.registry.TileRegistry
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
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * A [PipeBlockEntity] that additionally carries attachments: a
 * [net.kernelpanicsoft.boilerplate.pipe.hook.PipeHookType] on each face ([hooks], each its own
 * self-contained [HookHolderState] rather than one shared shape) and a
 * [PipeEncasementType][net.kernelpanicsoft.boilerplate.pipe.encasement.PipeEncasementType]
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
		val id = ResourceLocation.tryParse(tag.getString("type"))
		id?.let { HookTypeRegistry.byId(it) }?.createState()
	}

	/**
	 * The encasement wrapping this whole segment, or `null` if it carries none.
	 *
	 * `tryParse`, not `parse`, for the reason [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardState]'s
	 * own factory documents: a nested factory is called unguarded from `NestedNBTHolder.loadFrom`,
	 * so it has to answer for a tag with no `type` in it rather than throwing. A segment with no
	 * encasement no longer reaches here at all - an unset nested holder saves as an absent key
	 * instead of an empty compound - but a save written before that fix, or one damaged any other
	 * way, still does, and `parse` would take the whole block entity down with it.
	 */
	@Sync
	val encasement: NestedNBTHolder<EncasementHolderState> by nestedField { tag ->
		val id = ResourceLocation.tryParse(tag.getString("type"))
		id?.let { EncasementTypeRegistry.byId(it) }?.createState()
	}

	/**
	 * Which pipe type this hook block is standing in for, visually - the registry id of a
	 * [net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock] whose own baked model
	 * [net.kernelpanicsoft.boilerplate.pipe.client.MultipartBlockEntityVisual] draws for the
	 * pipe body. Defaults to [NONE] (no pipe placed here yet - see
	 * [net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock]'s KDoc); set to whatever block was
	 * actually placed/promoted, so a future second pipe type (e.g. a glass tier) keeps its own
	 * appearance after promotion instead of all hook blocks looking alike.
	 */
	@Sync
	var pipeBlockId: ResourceLocation by field(ResourceLocationSerializer) { NONE }

	/** Which attachment the next [createMenu] is for: a hooked face, or `null` for [encasement]. */
	var pendingMenuFace: Direction? = null

	/**
	 * Human-readable progress of the most recently submitted
	 * [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferJob] on any face - read by
	 * [net.kernelpanicsoft.boilerplate.pipe.gui.TerminalHookScreen]'s Craft tab via
	 * [net.kernelpanicsoft.archie.gui.blockentity.observeProperty]. Deliberately one flat field
	 * rather than per-face, matching how a player only ever has one terminal screen open at a
	 * time - a second face's own job would overwrite this while the first is still mid-flight, an
	 * accepted simplification for the rare case of two [net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookType]
	 * faces on the same block.
	 */
	@Sync
	var craftJobStatus: String by stringField { "" }

	/** [direction]'s own sorting-hook filter slot - a real single-slot [net.kernelpanicsoft.archie.transfer.ArchieItemStorage] restricted to filter cards, see [SortingHookState.filter]. */
	fun filterFor(direction: Direction) = (hooks[direction.name] as SortingHookState).filter

	/** Sums every attached hook's own [PipeHookType.basePressureCost] - `0` (the [PressureConsumer] default) for a segment with none set. */
	override val basePressureCost: Long get() = hooks.sumOf { (_, hookState) -> hookState.fromRegistry?.basePressureCost ?: 0 }

	/** See [basePressureCost] - the [PipeHookType.maxPressureDraw] equivalent. */
	override val maxPressureDraw: Long get() = hooks.sumOf { (_, hookState) -> hookState.fromRegistry?.maxPressureDraw ?: 0 }

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

	/** The encasement's own menu carries no face, so it gets the bare position - see each reader in [net.kernelpanicsoft.boilerplate.registry.GuiRegistry]. */
	override fun saveExtraData(buf: FriendlyByteBuf) {
		buf.writeBlockPos(blockPos)
		pendingMenuFace?.let { buf.writeEnum(it) }
	}

	private var firstTick = true

	override fun tick(level: Level, pos: BlockPos, state: BlockState) {
		super.tick(level, pos, state)
		if (level.isClientSide) return
		val serverLevel = level as ServerLevel
		// Consumed up front rather than cleared at the end: the encasement branch below returns
		// early for a segment that has none, which left firstTick stuck true forever on any
		// hook-only segment - re-running every hook's own start() on every single tick instead of
		// once. Latent only because no hook type currently overrides start(), which is exactly the
		// kind of thing that stops being latent the moment one does.
		val isFirstTick = firstTick
		firstTick = false
		if (hooks.size > 0) {
			var anyActiveChanged = false
			for ((directionName, hookState) in hooks) {
				val direction = Direction.valueOf(directionName)
				val hookType = hookState.fromRegistry ?: continue
				val wasActive = hookState.active
				hookState.active = drawHookPressure(serverLevel, pos, hookType)
				if (hookState.active != wasActive) anyActiveChanged = true
				if (!hookState.active) continue
				if (isFirstTick || !wasActive) hookType.start(serverLevel, pos, direction, this, hookState)
				hookType.tick(serverLevel, pos, direction, this, hookState)
				// Carrying a boundary crossing's second leg belongs to *being a source*, not to any
				// one hook type, so it is driven here for every hook that provides at all rather than
				// from each of their ticks - see BoundaryRelay.
				if (hookType.providesItems) BoundaryRelay.tick(serverLevel, pos, direction, this, hookState)
			}
			hooks.touch()
			// hooks is @Sync, but a menu-less BlockEntity's @Sync fields don't reach clients on
			// their own - without this, a hook's own on/off model (BistateHookModelBlock.ACTIVE via
			// PipeHookType.getRenderState) never visually updates no matter how long active's real
			// value has actually differed, since nothing ever pushes the change to nearby clients.
			if (anyActiveChanged) serverLevel.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL)
		}
		val encasementState = encasement.value ?: return
		if (isFirstTick) encasementState.fromRegistry?.start(serverLevel, pos, this, encasementState)
		encasementState.fromRegistry?.tick(serverLevel, pos, this, encasementState)
		encasement.touch()
	}

	/**
	 * Whether [hookType] can draw its own [PipeHookType.basePressureCost] from this segment's own
	 * [PressureLine] this tick, drawn for real (not merely simulated) when it can - bypasses
	 * [PressureConsumer]/[PressureConsumer.onPressureTick] entirely rather than implementing that
	 * interface (see [HookHolderState.active]'s own KDoc for why: a segment can carry several
	 * independent hooks, each needing its own gate check, not one aggregate multiplier). A zero-cost
	 * hook (no concrete type actually is one, but an addon's could be) always operates - matching
	 * [PressureConsumer]'s own "zero cost never gates" convention.
	 */
	private fun drawHookPressure(level: ServerLevel, pos: BlockPos, hookType: PipeHookType<*>): Boolean {
		val cost = hookType.basePressureCost
		if (cost <= 0) return true
		val line = PressureLine.find(level, pos) ?: return false
		if (line.extract(cost, true) < cost) return false
		line.extract(cost, false)
		return true
	}

	override fun setRemoved() {
		super.setRemoved()
		val serverLevel = level as? ServerLevel ?: return
		for ((directionName, hookState) in hooks) hookState.fromRegistry?.end(serverLevel, blockPos, Direction.valueOf(directionName), this, hookState)
		encasement.value?.let { it.fromRegistry?.end(serverLevel, blockPos, this, it) }
	}

	companion object {
		val NONE: ResourceLocation = Boilerplate.MOD % "none"

		fun tick(level: Level, pos: BlockPos, state: BlockState, tile: MultipartBlockEntity) = tile.tick(level, pos, state)
	}
}
