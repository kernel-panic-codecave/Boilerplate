package net.kernelpanicsoft.boilerplate.compat.emi

import dev.emi.emi.api.EmiEntrypoint

/**
 * Carries [EmiEntrypoint] - what NeoForge's own annotation scanning looks for - on a trivial
 * subclass instead of [BoilerplateEmiPlugin] itself. Putting the annotation directly on that
 * shared class crashed Fabric's own *unrelated* `"emi"` entrypoint construction instead: Fabric
 * Loader's Kotlin-reflection path (`ReflectKotlinClass.loadClassAnnotations`) eagerly reads *every*
 * annotation on a class it's about to instantiate as an entrypoint, and choked on `@EmiEntrypoint`
 * specifically with `java.lang.annotation.AnnotationFormatError: Attempt to create proxy for a
 * non-annotation type` - a genuine Loom/remap quirk with this particular zero-member annotation
 * type, confirmed against a real crash log, not something either loader's own EMI implementation
 * actually needs this class to carry. All real logic stays in [BoilerplateEmiPlugin]; this file
 * adds nothing but the marker.
 */
@EmiEntrypoint
class NeoForgeEmiEntrypoint : BoilerplateEmiPlugin()
