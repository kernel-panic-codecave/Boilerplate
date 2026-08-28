package net.kernelpanicsoft.boilerplate.datagen

import net.kernelpanicsoft.archie.data.client.model.ABlockStateProvider
import net.kernelpanicsoft.archie.data.client.model.AConfiguredModel
import net.kernelpanicsoft.archie.data.client.model.AModelFile
import net.kernelpanicsoft.archie.util.plus
import net.kernelpanicsoft.boilerplate.pipe.block.BistateHookModelBlock
import net.kernelpanicsoft.boilerplate.pipe.block.ConnectingEncasementModelBlock
import net.kernelpanicsoft.boilerplate.pipe.block.GlassPipeBlock
import net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.registry.Registrars
import net.kernelpanicsoft.boilerplate.warehouse.GantryRailBlock
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.EnumProperty

/**
 * Generates every blockstate/block-model/item-model JSON under `assets/boilerplate` -
 * `pipe`/`glass_pipe`/`pressure_pipe`'s connection-driven `"multipart"` bodies, `hook`'s unused
 * placeholder (its block is [net.minecraft.world.level.block.RenderShape.INVISIBLE] - see
 * [net.kernelpanicsoft.boilerplate.pipe.client.MultipartBlockEntityVisual], which draws a
 * promoted segment's pipe body/casing dynamically regardless of which pipe type - item or
 * pressure - it was promoted from), one item model per registered hook type and per registered
 * encasement type (item and pressure alike - one shared registry, see
 * [net.kernelpanicsoft.boilerplate.registry.EncasementTypeRegistry]) plus each type's own
 * part-block blockstate (see [net.kernelpanicsoft.boilerplate.pipe.block.PartBlock] - the
 * blockstate definition its
 * [getRenderState][net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType.getRenderState]
 * answers select variants from), the plain-cube warehouse controller block
 * plus its wand item,
 * [net.kernelpanicsoft.boilerplate.warehouse.GantryRailBlock]'s own connection-driven
 * `"multipart"` body (a real, player-visible block auto-placed as the gantry frame), and the
 * placeholder `gantry_head` model
 * [net.kernelpanicsoft.boilerplate.warehouse.client.WarehouseControllerBlockEntityRenderer]
 * looks up directly (registered as an [net.kernelpanicsoft.boilerplate.registry.ItemRegistry]
 * item purely so it bakes, not because it's player-obtainable - the gantry head is always a
 * dynamic render, never a placed block), the three plain-cube rack block types
 * ([net.kernelpanicsoft.boilerplate.warehouse.rack.GeneralRackBlockEntity]/[net.kernelpanicsoft.boilerplate.warehouse.rack.BulkRackBlockEntity]/
 * [net.kernelpanicsoft.boilerplate.warehouse.rack.UnstackableRackBlockEntity]). Replaces what was
 * previously hand-written JSON; running `./gradlew runDatagen` regenerates it in place under
 * `common/src/main/resources`.
 */
internal fun ABlockStateProvider.boilerplateBlockStates() {
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

	empty(BlockRegistry.Multipart)

	val warehouseController = blockModels().getExistingFile(modLoc("warehouse_controller"))
	simpleBlockWithItem(BlockRegistry.WarehouseController, warehouseController)
	itemModels().basicItem(ItemRegistry.WarehouseWand)

	val gantryRailCore = blockModels().getExistingFile(modLoc("gantry_rail_core"))
	val gantryRailArm = blockModels().getExistingFile(modLoc("gantry_rail_arm"))
	sixWayMultipart(BlockRegistry.GantryRail, GantryRailBlock.propertiesByDirection, gantryRailCore, gantryRailArm)
	itemModels().getBuilder("gantry_rail").parent(gantryRailCore)

	// PressurePipe renders its own connection-driven body exactly like Pipe above - the plain,
	// unpromoted form. A promoted pressure segment (the moment it carries an encasement) is just
	// BlockRegistry.Multipart - the same empty() placeholder above already covers it, since
	// MultipartBlockEntityVisual draws its pipe body/casing dynamically through Flywheel
	// regardless of which underlying pipe type it was promoted from.
	val pressurePipeCore = blockModels().getExistingFile(modLoc("pressure_pipe_core"))
	val pressurePipeArm = blockModels().getExistingFile(modLoc("pressure_pipe_arm"))
	sixWayMultipart(BlockRegistry.PressurePipe, PipeBlock.propertiesByDirection, pressurePipeCore, pressurePipeArm)
	itemModels().getBuilder("pressure_pipe").parent(pressurePipeCore)

	Registrars.HOOK_TYPE.ids.filter { it.namespace == mod.modId }.forEach { hookType ->
		itemModels().getBuilder(hookType.path + "_hook").parent(blockModels().getExistingFile(hookType + "_hook"))
	}

	// Same shape as the hook item models above, over the encasement registry instead - the
	// `<type path>_encasement` naming is what MultipartBlockEntityVisual's own casing model lookup relies
	// on, exactly as it relies on `<type path>_hook` for a hook.
	Registrars.ENCASEMENT_TYPE.ids.filter { it.namespace == mod.modId }.forEach { encasementType ->
		val part = BuiltInRegistries.BLOCK.get(encasementType + "_part")
		if (part is ConnectingEncasementModelBlock)
			itemModels().getBuilder(encasementType.path + "_encasement").parent(blockModels().getExistingFile(encasementType + "_encasement_core"))
		else
			itemModels().getBuilder(encasementType.path + "_encasement").parent(blockModels().getExistingFile(encasementType + "_encasement"))
	}

	// One blockstate per attachment part block - see PartBlock - a single default variant over the
	// same hand-modeled geometry the item icons above parent, except the crafting buffer's own
	// assembled multipart body below. This is what makes each hook/encasement
	// kind renderable as a real BlockState once MultipartBlockEntityVisual bakes
	// that function its part block renders exactly its `<type path>_hook`/`_encasement` geometry.
	// The air guard keeps a missing registration (namespace filter drift between here and
	// BlockRegistry) from silently writing a garbage blockstates/air.json.
	Registrars.HOOK_TYPE.ids.filter { it.namespace == mod.modId }.forEach { hookType ->
		val part = BuiltInRegistries.BLOCK.get(hookType + "_part")
		if (BuiltInRegistries.BLOCK.getKey(part) == hookType + "_part") {
			if (part is BistateHookModelBlock) {
				getVariantBuilder(part) {
					forAllStates { state ->
						AConfiguredModel.builder {
							modelFile(
								if (state.getValue(BistateHookModelBlock.ACTIVE))
									blockModels().getExistingFile(hookType + "_hook_on")
								else
									blockModels().getExistingFile(hookType + "_hook_off")
							)
						}.build()
					}
				}
			} else {
				simpleBlock(part, blockModels().getExistingFile(hookType + "_hook"))
			}
		}
	}
	Registrars.ENCASEMENT_TYPE.ids.filter { it.namespace == mod.modId }.forEach { encasementType ->
		val part = BuiltInRegistries.BLOCK.get(encasementType + "_part")
		if (BuiltInRegistries.BLOCK.getKey(part) == encasementType + "_part") {
			if (part is ConnectingEncasementModelBlock) {
				connectingEncasementMultipart(part, encasementType.path + "_encasement")
			} else {
				simpleBlock(part, blockModels().getExistingFile(encasementType + "_encasement"))
			}
		}
	}

	// The three rack block types each get their own hand-modeled shape (shelving/crates, a
	// riveted storage tank, a display case) matching their own functional distinction - same
	// hand-authored-model-plus-simpleBlockWithItem pattern the warehouse controller above uses.
	simpleBlockWithItem(BlockRegistry.GeneralRack, blockModels().getExistingFile(modLoc("general_rack")))
	simpleBlockWithItem(BlockRegistry.BulkRack, blockModels().getExistingFile(modLoc("bulk_rack")))
	simpleBlockWithItem(BlockRegistry.UnstackableRack, blockModels().getExistingFile(modLoc("unstackable_rack")))

	// Same plain-cube default as the racks above - creative/testing-only, so real art is low priority.
	simpleBlockWithItem(BlockRegistry.CreativePressureSource)

	// Filter cards are plain items (no block of their own), unlike a hook's block-model-backed
	// icon above - a flat `item/generated` icon over each one's own `textures/item/*.png` instead.
	itemModels().basicItem(ItemRegistry.ItemFilterCard)
	itemModels().basicItem(ItemRegistry.ModFilterCard)
	itemModels().basicItem(ItemRegistry.TagFilterCard)
	itemModels().basicItem(ItemRegistry.ColorFilterCard)
	itemModels().basicItem(ItemRegistry.RegexFilterCard)
	itemModels().basicItem(ItemRegistry.CombinedFilterCard)

	// Same flat item/generated icon as a filter card - a Pattern is likewise a plain item, no block
	// of its own. Its `encoded` predicate (registered in ItemRegistry.initClient) swaps the blank
	// punch-card deck for the punched one once a recipe is written to the stack.
	itemModels().basicItem(ItemRegistry.Pattern) {
		override {
			model(AModelFile(modLoc("item/pattern_encoded")))
			predicate(modLoc("encoded"), 1f)
		}
	}
	itemModels().basicItem(modLoc("pattern_encoded"))

	// The wrenches - handheld-parented so they render at vanilla's tool angle rather than flat-on.
	itemModels().basicItem(ItemRegistry.BrassWrench) { parent(AModelFile(mcLoc("item/handheld")))}
	itemModels().basicItem(ItemRegistry.DiamondWrench) { parent(AModelFile(mcLoc("item/handheld")))}
}

/**
 * [core] unconditionally, plus [arm] rotated onto each direction [propertiesByDirection] marks
 * connected - the shape every six-way connecting block in this mod uses
 * ([PipeBlock]/[net.kernelpanicsoft.boilerplate.pipe.block.GlassPipeBlock]'s original
 * hand-written `blockstates/pipe.json` shape, now generated identically, and
 * [net.kernelpanicsoft.boilerplate.warehouse.GantryRailBlock] reusing the same pattern).
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

/**
 * A [ConnectingEncasementModelBlock] part's own `"multipart"` body - the one attachment blockstate
 * that assembles a whole casing out of pieces rather than selecting one variant. Shared by every
 * kind registered over that block class (see its own KDoc): [modelPrefix] names which model set to
 * pull `_core`/`_arm`/`_cap`/`_edge`/`_corner` from (`"crafting_buffer_encasement"` for
 * [BlockRegistry.CraftingBufferPart], `"compressor_encasement"`/`"pressure_tank_encasement"` for
 * [BlockRegistry.CompressorPart]/[BlockRegistry.PressureTankPart]). The core renders
 * unconditionally; each face's arm/cap piece is selected by that direction's
 * [ConnectingEncasementModelBlock.FACES] mode and rotated onto it; edge and corner seam fillers render
 * only while [ConnectingEncasementModelBlock.FORMED] **and** every direction they bridge is ARM - an
 * incomplete arrangement shows bare cored arms, and any face without its arm also drops its seam
 * fillers so the casing keeps a smooth face there.
 */
private fun ABlockStateProvider.connectingEncasementMultipart(block: ConnectingEncasementModelBlock, modelPrefix: String) {
	val core = blockModels().getExistingFile(modLoc(modelPrefix + "_core"))
	val arm = blockModels().getExistingFile(modLoc(modelPrefix + "_arm"))
	val cap = blockModels().getExistingFile(modLoc(modelPrefix + "_cap"))
	val edge = blockModels().getExistingFile(modLoc(modelPrefix + "_edge"))
	val corner = blockModels().getExistingFile(modLoc(modelPrefix + "_corner"))

	getMultipartBuilder(block) {
		fun seamFiller(directions: List<Direction>) {
			configure {
				condition(ConnectingEncasementModelBlock.FORMED, true)
				for (direction in directions) condition(ConnectingEncasementModelBlock.FACES.getValue(direction), ConnectingEncasementModelBlock.FaceMode.ARM)
			}
		}

		part { modelFile(core) }

		for ((direction, property) in ConnectingEncasementModelBlock.FACES) {
			val rotationX = rotationXFor(direction)
			val rotationY = rotationYFor(direction)
			configure { condition(property, ConnectingEncasementModelBlock.FaceMode.ARM) }
			part {
				modelFile(arm)
				rotationX(rotationX)
				rotationY(rotationY)
			}
			configure { condition(property, ConnectingEncasementModelBlock.FaceMode.CAP) }
			part {
				modelFile(cap)
				rotationX(rotationX)
				rotationY(rotationY)
			}
		}

		for ((rotations, directions) in EDGE_ROTATIONS) {
			seamFiller(directions)
			part {
				modelFile(edge)
				rotationX(rotations.first)
				rotationY(rotations.second)
			}
		}

		for ((rotations, directions) in CORNER_ROTATIONS) {
			seamFiller(directions)
			part {
				modelFile(corner)
				rotationX(rotations.first)
				rotationY(rotations.second)
			}
		}
	}
}

/**
 * Rotation tables for the seam-filler pieces. Every casing piece model is authored on the north
 * face with east/up as the reference sides (`edge` hugging the block's north+east margins, `corner`
 * its north+east+up ones), so each entry names the rotation landing it on the listed direction set.
 * Derived from vanilla's own composition - `BlockModelRotation` builds
 * `rotateYXZ(-y, -x, 0)`, i.e. the model rotates around X first, then around Y, both by the negated
 * JSON angles - and verified against the proven arm rotations above: x=270/y=0 must carry an
 * authored-north piece onto UP, y=90 onto EAST, and so on. Under that reading the twelve non-dup
 * entries cover every valid edge pair (twelve) and every valid corner triple (eight - one direction
 * per axis; triples containing opposite directions aren't corners at all).
 */
private val EDGE_ROTATIONS: List<Pair<Pair<Int, Int>, List<Direction>>> = listOf(
	(0 to 0) to listOf(Direction.NORTH, Direction.EAST),
	(0 to 90) to listOf(Direction.SOUTH, Direction.EAST),
	(0 to 180) to listOf(Direction.SOUTH, Direction.WEST),
	(0 to 270) to listOf(Direction.NORTH, Direction.WEST),
	(90 to 0) to listOf(Direction.DOWN, Direction.EAST),
	(90 to 90) to listOf(Direction.DOWN, Direction.SOUTH),
	(90 to 180) to listOf(Direction.DOWN, Direction.WEST),
	(90 to 270) to listOf(Direction.DOWN, Direction.NORTH),
	(270 to 0) to listOf(Direction.UP, Direction.EAST),
	(270 to 90) to listOf(Direction.UP, Direction.SOUTH),
	(270 to 180) to listOf(Direction.UP, Direction.WEST),
	(270 to 270) to listOf(Direction.UP, Direction.NORTH),
)

private val CORNER_ROTATIONS: List<Pair<Pair<Int, Int>, List<Direction>>> = listOf(
	(0 to 0) to listOf(Direction.UP, Direction.NORTH, Direction.EAST),
	(0 to 90) to listOf(Direction.UP, Direction.SOUTH, Direction.EAST),
	(0 to 180) to listOf(Direction.UP, Direction.SOUTH, Direction.WEST),
	(0 to 270) to listOf(Direction.UP, Direction.NORTH, Direction.WEST),
	(90 to 0) to listOf(Direction.DOWN, Direction.NORTH, Direction.EAST),
	(90 to 90) to listOf(Direction.DOWN, Direction.SOUTH, Direction.EAST),
	(90 to 180) to listOf(Direction.DOWN, Direction.SOUTH, Direction.WEST),
	(90 to 270) to listOf(Direction.DOWN, Direction.NORTH, Direction.WEST),
)

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
