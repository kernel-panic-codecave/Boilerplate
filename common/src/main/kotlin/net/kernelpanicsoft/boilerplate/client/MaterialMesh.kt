package net.kernelpanicsoft.boilerplate.client

import dev.engine_room.flywheel.api.material.Material
import dev.engine_room.flywheel.api.model.Mesh
import dev.engine_room.flywheel.lib.material.Materials

/**
 * A [Mesh] that names the Flywheel [Material] it needs to be drawn correctly.
 *
 * Flywheel's material is chosen where a *model* is built, not where a mesh is, so a caller that only
 * has a mesh has to guess - and every caller here guessed [Materials.CUTOUT_BLOCK], which does alpha
 * *testing* (keep or discard per fragment) rather than blending. Anything genuinely translucent
 * drawn through it comes out either fully opaque or not at all, which is exactly as wrong as it
 * sounds for a droplet of gas.
 *
 * A mesh knows whether it is translucent; the instancing site does not and should not have to. So
 * the mesh says, and the site asks through [preferredMaterial].
 */
interface MaterialMesh : Mesh {
	/** The material this geometry must be drawn with. */
	val material: Material
}

/**
 * [this]'s own material if it has an opinion, else [fallback] - what an instancing site uses in
 * place of naming a material itself.
 *
 * The fallback exists because not every mesh reaching these sites is one of ours: Flywheel's own
 * baked-model meshes are perfectly ordinary cutout geometry and have no such opinion to offer.
 */
fun Mesh.preferredMaterial(fallback: Material = Materials.CUTOUT_BLOCK): Material =
	(this as? MaterialMesh)?.material ?: fallback
