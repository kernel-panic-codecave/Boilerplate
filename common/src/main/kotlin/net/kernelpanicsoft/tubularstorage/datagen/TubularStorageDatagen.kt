package net.kernelpanicsoft.tubularstorage.datagen

import net.kernelpanicsoft.archie.data.ADataGenerator
import net.kernelpanicsoft.archie.data.ADatagenEventObject
import net.kernelpanicsoft.tubularstorage.TubularStorage

/**
 * Registers Tubular Storage's datagen providers. Only ever touched from behind
 * [net.kernelpanicsoft.archie.data.platform.ADataGeneratorPlatform.isDataGen] - see
 * [TubularStorage.init] - so `archie-datagen-common`, a dev-only dependency absent from the
 * production runtime classpath, is never resolved outside of a `runDatagen` launch.
 */
internal object TubularStorageDatagen : ADatagenEventObject(TubularStorage.MOD) {
	override fun ADataGenerator.handler() {
		client {
			blockStates {
				tubularStorageBlockStates()
			}
		}
	}
}
