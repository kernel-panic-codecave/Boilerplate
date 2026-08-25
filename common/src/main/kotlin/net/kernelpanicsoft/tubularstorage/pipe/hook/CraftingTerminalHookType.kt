package net.kernelpanicsoft.tubularstorage.pipe.hook

import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.gui.CraftingTerminalHookMenu
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A [TerminalHookType] with a real, [CraftingTerminalHookState.grid]-backed crafting grid added -
 * "instant" network-backed crafting (see [CraftingTerminalHookState]), distinct from
 * [PatternProviderHookType]'s slow pattern-driven processing. Store/Craft tabs and job-queue
 * advancement work identically to a plain terminal - see `docs/design/m4-crafting-automation.md`.
 */
object CraftingTerminalHookType : PipeHookType<CraftingTerminalHookState>() {
	val ID: ResourceLocation = TubularStorage.MOD % "crafting_terminal"

	override val id: ResourceLocation get() = ID

	override fun createState(): CraftingTerminalHookState = CraftingTerminalHookState()

	override val hasMenu: Boolean = true

	override fun createMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity, direction: Direction): AbstractContainerMenu =
		CraftingTerminalHookMenu(id, inventory, tile, direction)

	override fun tick(level: ServerLevel, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity, state: CraftingTerminalHookState) {
		advanceTerminalJobs(level, pos, direction, tile, state)
	}

	override fun asItem(): Item = ItemRegistry.CraftingTerminalHook

	/** Identical to [TerminalHookType.shapesByDirection] - the same wide face plate, just with more menu behind it. */
	override val shapesByDirection: Map<Direction, VoxelShape> = mapOf(
		Direction.NORTH to Shapes.or(Shapes.box(0.0, 0.0, 0.0, 1.0, 1.0, 0.125), Shapes.box(0.375, 0.375, 0.125, 0.625, 0.625, 0.375)),
		Direction.SOUTH to Shapes.or(Shapes.box(0.0, 0.0, 0.875, 1.0, 1.0, 1.0), Shapes.box(0.375, 0.375, 0.625, 0.625, 0.625, 0.875)),
		Direction.WEST to Shapes.or(Shapes.box(0.0, 0.0, 0.0, 0.125, 1.0, 1.0), Shapes.box(0.125, 0.375, 0.375, 0.375, 0.625, 0.625)),
		Direction.EAST to Shapes.or(Shapes.box(0.875, 0.0, 0.0, 1.0, 1.0, 1.0), Shapes.box(0.625, 0.375, 0.375, 0.875, 0.625, 0.625)),
		Direction.DOWN to Shapes.or(Shapes.box(0.0, 0.0, 0.0, 1.0, 0.125, 1.0), Shapes.box(0.375, 0.125, 0.375, 0.625, 0.375, 0.625)),
		Direction.UP to Shapes.or(Shapes.box(0.0, 0.875, 0.0, 1.0, 1.0, 1.0), Shapes.box(0.375, 0.625, 0.375, 0.625, 0.875, 0.625)),
	)
}
