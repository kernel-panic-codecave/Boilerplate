package net.kernelpanicsoft.tubularstorage.power.network

import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.network.AbstractPipeNetworkManager
import net.kernelpanicsoft.tubularstorage.pipe.network.NetworkType
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel

/** The pressure network - see [NetworkType]. */
object PressureNetworkType : NetworkType() {
	val ID: ResourceLocation = TubularStorage.MOD % "pressure"

	override val id: ResourceLocation get() = ID

	override fun managerFor(level: ServerLevel): AbstractPipeNetworkManager<*> = PressurePipeNetworkManager.get(level)
}
