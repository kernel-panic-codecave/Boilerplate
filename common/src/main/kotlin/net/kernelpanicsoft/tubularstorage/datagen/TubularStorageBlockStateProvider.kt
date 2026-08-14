package net.kernelpanicsoft.tubularstorage.datagen

import net.kernelpanicsoft.archie.data.client.model.ABlockStateProvider
import net.kernelpanicsoft.archie.data.client.model.AModelFile
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation

/**
 * Generates every blockstate/block-model/item-model JSON under `assets/tubularstorage` -
 * `pipe`/`glass_pipe`'s connection-driven `"multipart"` bodies, `hook`'s unused placeholder (its
 * block is [net.minecraft.world.level.block.RenderShape.INVISIBLE] - see
 * [net.kernelpanicsoft.tubularstorage.pipe.client.PipeHookBlockEntityRenderer]), and the two hook
 * item models. Replaces what was previously hand-written JSON; running `./gradlew runDatagen`
 * regenerates it in place under `common/src/main/resources`.
 */
internal fun ABlockStateProvider.tubularStorageBlockStates() {
	val pipeCore = cuboidModel("pipe_core", blockTexture(BlockRegistry.Pipe), 6f, 6f, 6f, 10f, 10f, 10f)
	val pipeArm = cuboidModel("pipe_arm", blockTexture(BlockRegistry.Pipe), 6f, 6f, 0f, 10f, 10f, 6f)
	pipeMultipart(BlockRegistry.Pipe, pipeCore, pipeArm)
	itemModels().getBuilder("pipe").parent(pipeCore)

	val glassPipeCore = cuboidModel("glass_pipe_core", blockTexture(BlockRegistry.GlassPipe), 6f, 6f, 6f, 10f, 10f, 10f, translucent = true)
	val glassPipeArm = cuboidModel("glass_pipe_arm", blockTexture(BlockRegistry.GlassPipe), 6f, 6f, 0f, 10f, 10f, 6f, translucent = true)
	pipeMultipart(BlockRegistry.GlassPipe, glassPipeCore, glassPipeArm)
	itemModels().getBuilder("glass_pipe").parent(glassPipeCore)

	getMultipartBuilder(BlockRegistry.Hook) {
		part().modelFile(pipeCore).addModel().end()
	}

	hookModel("extraction_hook")
	hookModel("sorting_hook")
}

/** A cuboid element from ([fromX],[fromY],[fromZ]) to ([toX],[toY],[toZ]) textured [texture] on every face, UVs stretched to the full [0,16] range regardless of the cuboid's actual size. */
private fun ABlockStateProvider.cuboidModel(
	name: String,
	texture: ResourceLocation,
	fromX: Float, fromY: Float, fromZ: Float,
	toX: Float, toY: Float, toZ: Float,
	translucent: Boolean = false,
): AModelFile = blockModels().getBuilder(name) {
	parent(AModelFile("minecraft:block/block"))
	texture("particle", texture)
	texture("all", texture)
	if (translucent) renderType("minecraft:translucent")
	element {
		from(fromX, fromY, fromZ)
		to(toX, toY, toZ)
		allFaces { _, face -> face.texture("#all").uvs(0f, 0f, 16f, 16f) }
	}
}

/** [core] unconditionally, plus [arm] rotated onto each connected face - see `blockstates/pipe.json`'s original hand-written shape, now generated identically. */
private fun ABlockStateProvider.pipeMultipart(block: PipeBlock, core: AModelFile, arm: AModelFile) {
	getMultipartBuilder(block) {
		part().modelFile(core).addModel().end()
		for ((direction, property) in PipeBlock.propertiesByDirection) {
			part()
				.modelFile(arm)
				.rotationX(rotationXFor(direction))
				.rotationY(rotationYFor(direction))
				.addModel()
				.condition(property, true)
				.end()
		}
	}
}

private fun rotationXFor(direction: Direction): Int = when (direction) {
	Direction.UP -> 270
	Direction.DOWN -> 90
	else -> 0
}

private fun rotationYFor(direction: Direction): Int = when (direction) {
	Direction.SOUTH -> 180
	Direction.EAST -> 90
	Direction.WEST -> 270
	else -> 0
}

/** A hook's placeholder block model (see `docs/design/m1-pipe-network.md`) at `block/[name]`, and its item model inheriting it. */
private fun ABlockStateProvider.hookModel(name: String) {
	val model = cuboidModel(name, TubularStorage.MOD % "block/$name", 6f, 6f, 0f, 10f, 10f, 6f)
	itemModels().getBuilder(name).parent(model)
}
