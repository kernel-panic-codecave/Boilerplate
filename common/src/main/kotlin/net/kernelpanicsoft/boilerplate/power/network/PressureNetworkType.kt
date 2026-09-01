package net.kernelpanicsoft.boilerplate.power.network

import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.pipe.network.AbstractPipeNetworkManager
import net.kernelpanicsoft.boilerplate.pipe.network.NetworkType
import net.kernelpanicsoft.boilerplate.pipe.network.PipeCarriage
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel

/** The pressure network - see [NetworkType]. */
object PressureNetworkType : NetworkType() {
	val ID: ResourceLocation = Boilerplate.MOD % "pressure"

	override val id: ResourceLocation get() = ID

	override val genericPipeCarriage: PipeCarriage get() = PipeCarriage.SECONDARY

	override fun managerFor(level: ServerLevel): AbstractPipeNetworkManager<*> = PressurePipeNetworkManager.get(level)
}
