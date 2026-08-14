package net.kernelpanicsoft.tubularstorage.datagen

import net.kernelpanicsoft.archie.data.client.model.ABlockStateProvider
import net.kernelpanicsoft.archie.data.client.model.AModelFile
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.kernelpanicsoft.tubularstorage.warehouse.GantryRailBlock
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.properties.BooleanProperty

/**
 * Generates every blockstate/block-model/item-model JSON under `assets/tubularstorage` -
 * `pipe`/`glass_pipe`'s connection-driven `"multipart"` bodies, `hook`'s unused placeholder (its
 * block is [net.minecraft.world.level.block.RenderShape.INVISIBLE] - see
 * [net.kernelpanicsoft.tubularstorage.pipe.client.PipeHookBlockEntityRenderer]), the four hook item
 * models, the plain-cube warehouse controller block plus its wand item,
 * [net.kernelpanicsoft.tubularstorage.warehouse.GantryRailBlock]'s own connection-driven
 * `"multipart"` body (a real, player-visible block auto-placed as the gantry frame), and the
 * placeholder `gantry_head` model
 * [net.kernelpanicsoft.tubularstorage.warehouse.client.WarehouseControllerBlockEntityRenderer]
 * looks up directly (registered as an [net.kernelpanicsoft.tubularstorage.registry.ItemRegistry]
 * item purely so it bakes, not because it's player-obtainable - the gantry head is always a
 * dynamic render, never a placed block). Replaces what was previously hand-written JSON; running
 * `./gradlew runDatagen` regenerates it in place under `common/src/main/resources`.
 */
internal fun ABlockStateProvider.tubularStorageBlockStates() {
	val pipeCore = cuboidModel("pipe_core", blockTexture(BlockRegistry.Pipe), 6f, 6f, 6f, 10f, 10f, 10f)
	val pipeArm = cuboidModel("pipe_arm", blockTexture(BlockRegistry.Pipe), 6f, 6f, 0f, 10f, 10f, 6f)
	sixWayMultipart(BlockRegistry.Pipe, PipeBlock.propertiesByDirection, pipeCore, pipeArm)
	itemModels().getBuilder("pipe").parent(pipeCore)

	val glassPipeCore = cuboidModel("glass_pipe_core", blockTexture(BlockRegistry.GlassPipe), 6f, 6f, 6f, 10f, 10f, 10f, translucent = true)
	val glassPipeArm = cuboidModel("glass_pipe_arm", blockTexture(BlockRegistry.GlassPipe), 6f, 6f, 0f, 10f, 10f, 6f, translucent = true)
	sixWayMultipart(BlockRegistry.GlassPipe, PipeBlock.propertiesByDirection, glassPipeCore, glassPipeArm)
	itemModels().getBuilder("glass_pipe").parent(glassPipeCore)

	getMultipartBuilder(BlockRegistry.Hook) {
		part().modelFile(pipeCore).addModel().end()
	}

	hookModel("extraction_hook")
	hookModel("sorting_hook")
	hookModel("provider_hook")
	hookModel("requester_hook")
	warehouseTerminalHookModel("warehouse_terminal_hook")

	simpleBlockWithItem(BlockRegistry.WarehouseController)
	itemModels().basicItem(ItemRegistry.WarehouseWand)

	val gantryRailCore = cuboidModel("gantry_rail_core", blockTexture(BlockRegistry.GantryRail), 5f, 5f, 5f, 11f, 11f, 11f)
	val gantryRailArm = cuboidModel("gantry_rail_arm", blockTexture(BlockRegistry.GantryRail), 5f, 5f, 0f, 11f, 11f, 5f)
	sixWayMultipart(BlockRegistry.GantryRail, GantryRailBlock.propertiesByDirection, gantryRailCore, gantryRailArm)
	itemModels().getBuilder("gantry_rail").parent(gantryRailCore)

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

/**
 * [core] unconditionally, plus [arm] rotated onto each direction [propertiesByDirection] marks
 * connected - the shape every six-way connecting block in this mod uses
 * ([PipeBlock]/[net.kernelpanicsoft.tubularstorage.pipe.block.GlassPipeBlock]'s original
 * hand-written `blockstates/pipe.json` shape, now generated identically, and
 * [net.kernelpanicsoft.tubularstorage.warehouse.GantryRailBlock] reusing the same pattern).
 */
private fun ABlockStateProvider.sixWayMultipart(block: Block, propertiesByDirection: Map<Direction, BooleanProperty>, core: AModelFile, arm: AModelFile) {
	getMultipartBuilder(block) {
		part().modelFile(core).addModel().end()
		for ((direction, property) in propertiesByDirection) {
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

/**
 * The warehouse terminal hook's own model - unlike [hookModel]'s small centered box, a full 16x16
 * face plate (a terminal panel bolted onto the pipe, not a plain fitting) at the outward end,
 * facing away from the pipe core the same way every other hook's visible/interactable face does,
 * with a short connecting strut bridging it back to the core. Same total depth as [hookModel]'s box
 * so it doesn't clip through or float clear of the pipe body it's attached to.
 */
private fun ABlockStateProvider.warehouseTerminalHookModel(name: String) {
	val texture = TubularStorage.MOD % "block/$name"
	val model = blockModels().getBuilder(name) {
		parent(AModelFile("minecraft:block/block"))
		texture("particle", texture)
		texture("all", texture)
		element {
			from(6f, 6f, 2f)
			to(10f, 10f, 6f)
			allFaces { _, face -> face.texture("#all").uvs(0f, 0f, 16f, 16f) }
		}
		element {
			from(0f, 0f, 0f)
			to(16f, 16f, 2f)
			allFaces { _, face -> face.texture("#all").uvs(0f, 0f, 16f, 16f) }
		}
	}
	itemModels().getBuilder(name).parent(model)
}
