package net.kernelpanicsoft.tubularstorage.pipe.hook

import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.gui.SortingHookMenu
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item

/**
 * Opts the attached (non-pipe) inventory into being pullable by network requests (see
 * [RequesterHookType]) - deliberately explicit rather than every reachable inventory automatically
 * being a source (`docs/design/m3-warehouse-storage.md`, decision #5 in `README.md`). Purely
 * passive: unlike [ExtractionHookType], a provider hook never initiates anything on its own, it's
 * only consulted while resolving a request. [ProviderHookState]'s own filter/mode (edited via the
 * same [SortingHookMenu] [FilterHookType]/[SyncHookType] use) narrows *which* items a request may
 * pull - most relevant facing an [InterfaceHookType] hook, where it forms a filtered, extract-only
 * subnet boundary (see `docs/design/m2-sorting-routing.md`).
 */
object ProviderHookType : PipeHookType<ProviderHookState>() {
	val ID: ResourceLocation = TubularStorage.MOD % "provider"

	override fun createState(): ProviderHookState = ProviderHookState()

	override fun asItem(): Item = ItemRegistry.ProviderHook

	override val providesItems: Boolean = true

	override val hasMenu: Boolean = true

	override fun createMenu(id: Int, inventory: Inventory, tile: HookBlockEntity, direction: Direction): AbstractContainerMenu =
		SortingHookMenu(id, inventory, tile, direction)
}
