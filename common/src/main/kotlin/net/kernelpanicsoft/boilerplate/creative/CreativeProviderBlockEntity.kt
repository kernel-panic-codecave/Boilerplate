package net.kernelpanicsoft.boilerplate.creative

import dev.architectury.registry.menu.ExtendedMenuProvider
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.block.entity.NBTBlockEntity
import net.kernelpanicsoft.archie.serialization.Sync
import net.kernelpanicsoft.boilerplate.registry.TileRegistry
import net.kernelpanicsoft.boilerplate.resource.ResourceComponentSerializer
import net.kernelpanicsoft.boilerplate.resource.SResourceComponent
import net.kernelpanicsoft.boilerplate.resource.displayName
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * Backs [CreativeProviderBlock] - a creative-tab-only, never-craftable block offering an endless
 * supply of one configured resource, for testing a network and for creative-mode building without
 * first having to produce what the network is supposed to move.
 *
 * The resource half of what [net.kernelpanicsoft.boilerplate.power.entity.CreativePressureSourceBlockEntity]
 * does for pressure, and built the same way: an ordinary capability that simply never runs down,
 * rather than a special case anything downstream has to know about. Face a provider hook at one and
 * its resource becomes network stock a terminal will offer and a crafting plan will count as
 * already satisfied - see [InfiniteResourceStorage].
 *
 * Any registered kind, not only items: what it provides is a
 * [net.kernelpanicsoft.boilerplate.resource.ResourceKind]-tagged resource set through the screen's
 * own ghost slot, so a bottomless source of water or of an addon's chemical is the same block.
 */
class CreativeProviderBlockEntity(pos: BlockPos, state: BlockState) :
	NBTBlockEntity(TileRegistry.CreativeProvider, pos, state), ExtendedMenuProvider {

	/**
	 * What this block provides, or blank while nothing has been set - in which case it offers no
	 * capability at all rather than an empty one, so an unconfigured block is invisible to the
	 * network instead of being a destination that answers every query with nothing.
	 */
	@Sync
	var provided: SResourceComponent by field(ResourceComponentSerializer) { ItemResource.BLANK }

	val storage: InfiniteResourceStorage = InfiniteResourceStorage { provided }

	/**
	 * Server-side: sets what this provides. A blank [resource] turns it off.
	 *
	 * Re-shapes the neighbours afterwards, which the write alone does not do. A pipe forms an arm
	 * only toward a block whose capability actually resolves
	 * ([net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock]'s own `canConnect`), and an
	 * unconfigured provider deliberately exposes none - so a pipe placed against one starts
	 * unconnected and would never look again. Setting the resource has to prompt it.
	 *
	 * Nothing here pushes the value to clients: a `@Sync` field's own delegate does that on write
	 * (see Archie's `NBTHolderImpl`), so saying it again would only send the same packet twice.
	 */
	fun provide(resource: ResourceComponent) {
		provided = resource
		setChanged()
		val level = level ?: return
		blockState.updateNeighbourShapes(level, blockPos, Block.UPDATE_ALL)
	}

	override fun saveExtraData(buf: FriendlyByteBuf) {
		buf.writeBlockPos(blockPos)
	}

	override fun getDisplayName(): Component =
		if (provided.isBlank) Component.literal("Creative Provider")
		else Component.literal("Creative Provider: ").append(provided.displayName())

	override fun createMenu(i: Int, inventory: Inventory, player: Player): AbstractContainerMenu =
		CreativeProviderMenu(i, inventory, this)
}
