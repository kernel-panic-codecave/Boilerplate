package net.kernelpanicsoft.boilerplate.power

import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.registry.NetworkTypeRegistry
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block

/**
 * A pressure tank: a passive [PressureTankEncasementState.pressure] buffer, one endpoint on the
 * pressure network - see `docs/design/m5-pressure-power.md`. No GUI - right-clicking without a
 * wrench does nothing.
 */
object PressureTankEncasementType : PressureEncasementType<PressureTankEncasementState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "pressure_tank"

	override val id: ResourceLocation get() = ID

	override val partBlock: Block get() = BlockRegistry.PressureTankPart

	/** Attachable only on a dedicated pressure-pipe segment (see [net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType.compatibleNetworkTypes]). */
	override val compatibleNetworkTypes by lazy { setOf(NetworkTypeRegistry.Pressure) }

	override fun createState(): PressureTankEncasementState = PressureTankEncasementState()

	override fun asItem(): Item = ItemRegistry.PressureTankEncasement
}
