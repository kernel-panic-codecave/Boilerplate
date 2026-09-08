package net.kernelpanicsoft.boilerplate.power.network

import earth.terrarium.common_storage_lib.lookup.BlockLookup
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.power.PressureApi
import net.kernelpanicsoft.boilerplate.pipe.network.AbstractPipeNetworkManager
import net.kernelpanicsoft.boilerplate.pipe.network.NetworkType
import net.kernelpanicsoft.boilerplate.pipe.network.PipeCarriage
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel

/** The pressure network - see [NetworkType]. */
object PressureNetworkType : NetworkType() {
	val ID: ResourceLocation = Boilerplate.MOD % "pressure"

	override val id: ResourceLocation get() = ID

	override val genericPipeCarriage: PipeCarriage get() = PipeCarriage.SECONDARY

	/**
	 * Pressure carries no resource, so it has no [net.kernelpanicsoft.boilerplate.pipe.network.ResourceNetworkType.api]
	 * of its own - but a pipe conducting it still has to connect to a plain block that exposes one,
	 * which is exactly what [NetworkType.externalLookup] is for.
	 */
	override val externalLookup: BlockLookup<*, Direction?> get() = PressureApi.BLOCK

	override fun managerFor(level: ServerLevel): AbstractPipeNetworkManager<*> = PressurePipeNetworkManager.get(level)
}
