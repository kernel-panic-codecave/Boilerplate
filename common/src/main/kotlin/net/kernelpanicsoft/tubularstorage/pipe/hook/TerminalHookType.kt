package net.kernelpanicsoft.tubularstorage.pipe.hook

import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.gui.TerminalHookMenu
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Turns the attached face into a search/withdraw window over every
 * [net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity] reachable on the
 * network - see `docs/design/m3-warehouse-storage.md`. Purely a menu, like [FilterHookType] -
 * no per-tick behavior, the search itself runs on demand
 * ([TerminalHookMenu.sendSearchResults]) rather than continuously.
 */
object TerminalHookType : PipeHookType<TerminalHookState>() {
	val ID: ResourceLocation = TubularStorage.MOD % "terminal"

	override fun createState(): TerminalHookState = TerminalHookState()

	override val hasMenu: Boolean = true

	override fun createMenu(id: Int, inventory: Inventory, tile: HookBlockEntity, direction: Direction): AbstractContainerMenu =
		TerminalHookMenu(id, inventory, tile, direction)

	override fun asItem(): Item = ItemRegistry.TerminalHook

	/**
	 * Wider than [DEFAULT_SHAPES]: a full-face plate (pixels 0-2 deep) plus the same 4x4 strut every
	 * other hook has (pixels 2-6), unioned per face - matches `terminal_hook.json`'s own two
	 * elements (`[0,0,0]`-`[16,16,2]` outward plate, `[6,6,2]`-`[10,10,6]` strut reaching to the
	 * pipe), where every other hook model is just the strut alone.
	 */
	override val shapesByDirection: Map<Direction, VoxelShape> = mapOf(
		Direction.NORTH to Shapes.or(Shapes.box(0.0, 0.0, 0.0, 1.0, 1.0, 0.125), Shapes.box(0.375, 0.375, 0.125, 0.625, 0.625, 0.375)),
		Direction.SOUTH to Shapes.or(Shapes.box(0.0, 0.0, 0.875, 1.0, 1.0, 1.0), Shapes.box(0.375, 0.375, 0.625, 0.625, 0.625, 0.875)),
		Direction.WEST to Shapes.or(Shapes.box(0.0, 0.0, 0.0, 0.125, 1.0, 1.0), Shapes.box(0.125, 0.375, 0.375, 0.375, 0.625, 0.625)),
		Direction.EAST to Shapes.or(Shapes.box(0.875, 0.0, 0.0, 1.0, 1.0, 1.0), Shapes.box(0.625, 0.375, 0.375, 0.875, 0.625, 0.625)),
		Direction.DOWN to Shapes.or(Shapes.box(0.0, 0.0, 0.0, 1.0, 0.125, 1.0), Shapes.box(0.375, 0.125, 0.375, 0.625, 0.375, 0.625)),
		Direction.UP to Shapes.or(Shapes.box(0.0, 0.875, 0.0, 1.0, 1.0, 1.0), Shapes.box(0.375, 0.625, 0.375, 0.625, 0.875, 0.625)),
	)
}
