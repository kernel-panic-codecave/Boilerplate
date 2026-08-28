package net.kernelpanicsoft.tubularstorage.pipe.hook

import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.gui.SortingHookMenu
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.kernelpanicsoft.tubularstorage.registry.NetworkTypeRegistry
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item

object SyncHookType : PipeHookType<SyncHookState>() {
	val ID: ResourceLocation = TubularStorage.MOD % "sync"

	override val id: ResourceLocation get() = ID

	/** Attachable only on an item-pipe segment (see [net.kernelpanicsoft.tubularstorage.pipe.attachment.PipeAttachmentType.compatibleNetworkTypes]). */
	override val compatibleNetworkTypes = setOf(NetworkTypeRegistry.Item)

	/** [PipeHookType.basePressureCost] - A pure routing config - no active per-tick work of its own, just the lightest idle draw to stay online. */
	override val basePressureCost: Long = 1L

	override fun createState(): SyncHookState = SyncHookState()

	override val hasMenu: Boolean = true

	override val validRoute: Boolean = true

	override val providesItems: Boolean = true

	override fun createMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity, direction: Direction): AbstractContainerMenu =
		SortingHookMenu(id, inventory, tile, direction)

	override fun asItem(): Item = ItemRegistry.SyncHook
}