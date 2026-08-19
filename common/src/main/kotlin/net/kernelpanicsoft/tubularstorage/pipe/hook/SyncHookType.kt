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

object SyncHookType : PipeHookType<SyncHookState>() {
	val ID: ResourceLocation = TubularStorage.MOD % "sync"

	override fun createState(): SyncHookState = SyncHookState()

	override val hasMenu: Boolean = true

	override val validRoute: Boolean = true

	override val providesItems: Boolean = true

	override fun createMenu(id: Int, inventory: Inventory, tile: HookBlockEntity, direction: Direction): AbstractContainerMenu =
		SortingHookMenu(id, inventory, tile, direction)

	override fun asItem(): Item = ItemRegistry.SyncHook
}