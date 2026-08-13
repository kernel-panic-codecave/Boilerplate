package net.kernelpanicsoft.tubularstorage.network

import net.kernelpanicsoft.archie.networking.NetworkChannel
import net.kernelpanicsoft.archie.util.onClient
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage

/** Tubular Storage's own network channel, separate from Archie's internal one. */
object TubularStorageNetworkChannel : NetworkChannel(TubularStorage.MOD % "main") {
	fun init() {
		onClient {
			clientbound(PipeContentsSyncPacket::class) { packet, _ -> packet.handleOnClient() }
		}
		register()
	}
}
