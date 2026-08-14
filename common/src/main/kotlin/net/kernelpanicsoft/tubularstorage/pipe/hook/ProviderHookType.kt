package net.kernelpanicsoft.tubularstorage.pipe.hook

import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.minecraft.resources.ResourceLocation

/**
 * Opts the attached (non-pipe) inventory into being pullable by network requests (see
 * [RequesterHookType]) - deliberately explicit rather than every reachable inventory automatically
 * being a source (`docs/design/m3-warehouse-storage.md`, decision #5 in `README.md`). Purely
 * passive: unlike [ExtractionHookType], a provider hook never initiates anything on its own, it's
 * only consulted while resolving a request.
 */
object ProviderHookType : PipeHookType<ProviderHookState>() {
	val ID: ResourceLocation = TubularStorage.MOD % "provider"

	override fun createState(): ProviderHookState = ProviderHookState()
}
