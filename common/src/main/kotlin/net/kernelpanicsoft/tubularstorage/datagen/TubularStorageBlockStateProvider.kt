package net.kernelpanicsoft.tubularstorage.datagen

import net.kernelpanicsoft.archie.data.client.model.ABlockStateProvider
import net.kernelpanicsoft.archie.data.client.model.AModelFile
import net.kernelpanicsoft.archie.util.plus
import net.kernelpanicsoft.tubularstorage.pipe.block.GlassPipeBlock
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.kernelpanicsoft.tubularstorage.registry.Registrars
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.kernelpanicsoft.tubularstorage.warehouse.GantryRailBlock
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.EnumProperty

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
 * dynamic render, never a placed block), and the three plain-cube rack block types
 * ([net.kernelpanicsoft.tubularstorage.warehouse.rack.GeneralRackBlockEntity]/[net.kernelpanicsoft.tubularstorage.warehouse.rack.BulkRackBlockEntity]/
 * [net.kernelpanicsoft.tubularstorage.warehouse.rack.UnstackableRackBlockEntity]). Replaces what
 * was previously hand-written JSON; running `./gradlew runDatagen` regenerates it in place under
 * `common/src/main/resources`.
 */
internal fun ABlockStateProvider.tubularStorageBlockStates() {
	val pipeCore = blockModels().getExistingFile(modLoc("pipe_core"))
	val pipeArm = blockModels().getExistingFile(modLoc("pipe_arm"))
	sixWayMultipart(BlockRegistry.Pipe, PipeBlock.propertiesByDirection, pipeCore, pipeArm)
	itemModels().getBuilder("pipe").parent(pipeCore)

	val glassPipeCore = blockModels().getExistingFile(modLoc("pipe_core_glass"))
	val glassPipeArm = blockModels().getExistingFile(modLoc("pipe_arm_glass"))
	val glassPipeStraight = blockModels().getExistingFile(modLoc("pipe_straight_glass"))
	sixWayMultipart(BlockRegistry.GlassPipe, PipeBlock.propertiesByDirection, glassPipeCore, glassPipeArm, null,
		GlassPipeBlock.straightProperty, BlockStateProperties.AXIS, glassPipeStraight)
	itemModels().getBuilder("glass_pipe").parent(glassPipeCore)

	empty(BlockRegistry.Hook)

	val warehouseController = blockModels().getExistingFile(modLoc("warehouse_controller"))
	simpleBlockWithItem(BlockRegistry.WarehouseController, warehouseController)
	itemModels().basicItem(ItemRegistry.WarehouseWand)

	val gantryRailCore = blockModels().getExistingFile(modLoc("gantry_rail_core"))
	val gantryRailArm = blockModels().getExistingFile(modLoc("gantry_rail_arm"))
	sixWayMultipart(BlockRegistry.GantryRail, GantryRailBlock.propertiesByDirection, gantryRailCore, gantryRailArm)
	itemModels().getBuilder("gantry_rail").parent(gantryRailCore)
	Registrars.HOOK_TYPE.ids.filter { it.namespace == mod.modId }.forEach { hookType ->
		itemModels().getBuilder(hookType.path + "_hook").parent(blockModels().getExistingFile(hookType + "_hook"))
	}

	// The three rack block types are plain cubes - a single flat `textures/block/*.png` per type
	// via `cubeAll`/`simpleBlockWithItem`'s defaults, unlike the warehouse controller's own
	// hand-modeled Blockbench shape above.
	simpleBlockWithItem(BlockRegistry.GeneralRack)
	simpleBlockWithItem(BlockRegistry.BulkRack)
	simpleBlockWithItem(BlockRegistry.UnstackableRack)

	// Filter cards are plain items (no block of their own), unlike a hook's block-model-backed
	// icon above - a flat `item/generated` icon over each one's own `textures/item/*.png` instead.
	itemModels().basicItem(ItemRegistry.ItemFilterCard)
	itemModels().basicItem(ItemRegistry.ModFilterCard)
	itemModels().basicItem(ItemRegistry.TagFilterCard)
	itemModels().basicItem(ItemRegistry.ColorFilterCard)
	itemModels().basicItem(ItemRegistry.RegexFilterCard)
	itemModels().basicItem(ItemRegistry.CombinedFilterCard)
}

/**
 * [core] unconditionally, plus [arm] rotated onto each direction [propertiesByDirection] marks
 * connected - the shape every six-way connecting block in this mod uses
 * ([PipeBlock]/[net.kernelpanicsoft.tubularstorage.pipe.block.GlassPipeBlock]'s original
 * hand-written `blockstates/pipe.json` shape, now generated identically, and
 * [net.kernelpanicsoft.tubularstorage.warehouse.GantryRailBlock] reusing the same pattern).
 */
private fun ABlockStateProvider.sixWayMultipart(block: Block, propertiesByDirection: Map<Direction, BooleanProperty>, core: AModelFile, arm: AModelFile, cap: AModelFile? = null, straightProperty: BooleanProperty? = null, axisProperty: EnumProperty<Direction.Axis>? = null, straight: AModelFile? = null) {
	getMultipartBuilder(block) {
		if (straightProperty != null && axisProperty != null && straight != null)
		{
			for (axis in Direction.Axis.entries) {
				configure {
					condition(straightProperty, true)
					condition(axisProperty, axis)
				}
				part {
					modelFile(straight)
					val direction = when (axis) {
						Direction.Axis.X -> Direction.WEST
						Direction.Axis.Y -> Direction.DOWN
						Direction.Axis.Z -> Direction.NORTH
					}
					rotationX(rotationXFor(direction))
					rotationY(rotationYFor(direction))
				}
				configure {
					condition(straightProperty, false)
				}
				part { modelFile(core) }
			}
		}
		else
		{
			part { modelFile(core) }
		}

		for ((direction, property) in propertiesByDirection)
		{
			configure {
				if (straightProperty != null ) condition(straightProperty, false)
				condition(property, true)
			}
			part {
				modelFile(arm)
				rotationX(rotationXFor(direction))
				rotationY(rotationYFor(direction))
			}
			if (cap == null) continue
			configure {
				if (straightProperty != null ) condition(straightProperty, false)
				condition(property, false)
			}
			part {
				modelFile(cap)
				rotationX(rotationXFor(direction))
				rotationY(rotationYFor(direction))
			}
		}

	}
}

private fun ABlockStateProvider.empty(block: Block) {
	getMultipartBuilder(block) {
		part {
			modelFile(blockModels().getExistingFile(mcLoc("air")))
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
