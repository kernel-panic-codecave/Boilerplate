package net.kernelpanicsoft.tubularstorage.datagen

import net.kernelpanicsoft.archie.data.client.model.ABlockStateProvider
import net.kernelpanicsoft.archie.data.client.model.AModelFile
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation

/**
 * Generates every blockstate/block-model/item-model JSON under `assets/tubularstorage` -
 * `pipe`/`glass_pipe`'s connection-driven `"multipart"` bodies, `hook`'s unused placeholder (its
 * block is [net.minecraft.world.level.block.RenderShape.INVISIBLE] - see
 * [net.kernelpanicsoft.tubularstorage.pipe.client.PipeHookBlockEntityRenderer]), the four hook item
 * models, the plain-cube warehouse controller block plus its wand item, and the placeholder
 * `gantry_rail`/`gantry_head` models
 * [net.kernelpanicsoft.tubularstorage.warehouse.client.WarehouseControllerBlockEntityRenderer]
 * looks up directly (registered as [net.kernelpanicsoft.tubularstorage.registry.ItemRegistry] items
 * purely so they bake, not because they're player-obtainable). Replaces what was previously
 * hand-written JSON; running `./gradlew runDatagen` regenerates it in place under
 * `common/src/main/resources`.
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
	hookModel("provider_hook")
	hookModel("requester_hook")

	simpleBlockWithItem(BlockRegistry.WarehouseController)
	itemModels().basicItem(ItemRegistry.WarehouseWand)

	val gantryRail = cuboidModel("gantry_rail", TubularStorage.MOD % "block/gantry_rail", 4f, 7f, 4f, 12f, 9f, 12f, translucent = true)
	itemModels().getBuilder("gantry_rail").parent(gantryRail)

	val gantryHead = cuboidModel("gantry_head", TubularStorage.MOD % "block/gantry_head", 3f, 3f, 3f, 13f, 13f, 13f)
	itemModels().getBuilder("gantry_head").parent(gantryHead)
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
