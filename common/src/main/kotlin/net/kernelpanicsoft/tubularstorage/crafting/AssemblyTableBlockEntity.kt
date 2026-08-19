package net.kernelpanicsoft.tubularstorage.crafting

import dev.architectury.registry.menu.ExtendedMenuProvider
import net.kernelpanicsoft.archie.block.entity.NBTBlockEntity
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.tubularstorage.crafting.gui.AssemblyTableMenu
import net.kernelpanicsoft.tubularstorage.registry.TileRegistry
import net.minecraft.Util
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.block.state.BlockState

/**
 * A real, pipe-fed block that both stores encoded [Pattern]s and (see the pipe-fed processing work
 * still to come) executes them, rather than resolving a craft instantly - `docs/design/m4-crafting-automation.md`'s
 * "Physical Assembly Table" decision. [grid]/[output] are one shared 3x3-plus-result shape used for
 * both purposes: a player manually filling [grid] (and, for a [PatternKind.PROCESSING] pattern,
 * [output] too) and encoding it via [AssemblyTableMenu.encode] is exactly the same slots pipes
 * later feed to run an already-encoded pattern against.
 */
class AssemblyTableBlockEntity(pos: BlockPos, state: BlockState) :
	NBTBlockEntity(TileRegistry.AssemblyTable, pos, state), ExtendedMenuProvider {

	val patterns: MutableList<Pattern> by listField(Pattern.serializer()) { emptyList() }
	val grid: ArchieItemStorage by itemField(Pattern.GRID_SIZE)
	val output: ArchieItemStorage by itemField(1)

	override fun createMenu(id: Int, inventory: Inventory, player: Player): AbstractContainerMenu =
		AssemblyTableMenu(id, inventory, this)

	override fun getDisplayName(): Component = Component.translatable(Util.makeDescriptionId("container", BuiltInRegistries.BLOCK.getKey(blockState.block)))

	override fun saveExtraData(buf: FriendlyByteBuf) {
		buf.writeBlockPos(blockPos)
	}
}
