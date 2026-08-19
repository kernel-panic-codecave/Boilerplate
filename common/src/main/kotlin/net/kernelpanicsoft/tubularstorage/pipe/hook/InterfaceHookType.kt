package net.kernelpanicsoft.tubularstorage.pipe.hook

import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.gui.InterfaceHookMenu
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item

/**
 * A passive stock buffer, exposed to [earth.terrarium.common_storage_lib.item.ItemApi.BLOCK] on
 * its own face (see [net.kernelpanicsoft.tubularstorage.registry.TileRegistry.Hook]) so anything
 * physically touching it - a hopper, another mod's pipe, or another Tubular Storage hook facing it
 * directly - can insert/extract like it would against any ordinary inventory. Unlike
 * [ExtractionHookType]/[RequesterHookType], never initiates anything on its own tick - it's purely
 * a passive target, the same "passive" half of the source/sink taxonomy [ProviderHookType]/
 * [FilterHookType] already occupy (`docs/design/m2-sorting-routing.md`'s hook taxonomy table), just
 * for direct physical access rather than network routing/requests.
 *
 * Also the anchor of Tubular Storage's subnet-boundary system (see
 * `docs/design/m2-sorting-routing.md`'s "hook-to-hook facing" section): a hook facing directly into
 * an interface hook (or vice versa) keeps the two sides' pipe networks logically separate rather
 * than merging them, with the *other* hook's own type determining how the junction behaves -
 * [ProviderHookType] (extract-only, now filtered), [FilterHookType] (insert-only, filtered),
 * [SyncHookType] (filtered two-way), [ExtractionHookType] (actively pulls this hook's [stock][InterfaceHookState.stock]
 * out), [RequesterHookType] (actively keeps [stock][InterfaceHookState.stock] topped up). See
 * [net.kernelpanicsoft.tubularstorage.pipe.network.SubnetBoundary] for where that's actually
 * implemented.
 */
object InterfaceHookType : PipeHookType<InterfaceHookState>() {
	val ID: ResourceLocation = TubularStorage.MOD % "interface"

	override fun createState(): InterfaceHookState = InterfaceHookState()

	override val hasMenu: Boolean = true

	override val validRoute: Boolean = true

	override fun createMenu(id: Int, inventory: Inventory, tile: HookBlockEntity, direction: Direction): AbstractContainerMenu =
		InterfaceHookMenu(id, inventory, tile, direction)

	override fun asItem(): Item = ItemRegistry.InterfaceHook
}
