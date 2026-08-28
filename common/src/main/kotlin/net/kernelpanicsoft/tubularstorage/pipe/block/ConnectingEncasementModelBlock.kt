package net.kernelpanicsoft.tubularstorage.pipe.block

import com.mojang.serialization.MapCodec
import net.minecraft.core.Direction
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import java.util.*

/**
 * [EncasementModelBlock] subclass carrying the "open frame casing with a per-face arm/cap/none
 * variant, clustering into a bigger structure" shape every encasement kind that needs it shares -
 * registered once per kind ([net.kernelpanicsoft.tubularstorage.crafting.CraftingBufferEncasementType]'s
 * own `crafting_buffer_part`, and [net.kernelpanicsoft.tubularstorage.power.CompressorEncasementType]/
 * [net.kernelpanicsoft.tubularstorage.power.PressureTankEncasementType]'s `compressor_part`/
 * `pressure_tank_part`), each over its own model set and its own cluster-validity rule
 * ([net.kernelpanicsoft.tubularstorage.crafting.CraftingCpuManager]/
 * [net.kernelpanicsoft.tubularstorage.power.PressureMultiblockManager]) - the properties
 * themselves are generic to the shape, not to any one kind.
 *
 * - each face's own [FACES] mode answers what that side shows toward its neighbor:
 *   [FaceMode.ARM] where another member of the same casing kind sits (the arm bridging the pair),
 *   [FaceMode.CAP] where the segment carries a pipe that ends here unconnected (the cap plugging
 *   the casing's pipe hole), and [FaceMode.NONE] where the opening is left as-is (the pipe passes
 *   through, a hook covers it, or there is nothing to plug at all).
 *
 * - [FORMED] mirrors the synced [net.kernelpanicsoft.tubularstorage.pipe.encasement.EncasementHolderState.formed]
 *   flag on the member's own encasement state - the blockstate definition gates its edge/corner
 *   pieces on it, so only a cluster whose members form a valid structure renders the seam fillers
 *   between adjacent arms.
 */
class ConnectingEncasementModelBlock(properties: Properties) : EncasementModelBlock(properties) {
	init {
		registerDefaultState(stateDefinition.any())
	}

	override fun codec(): MapCodec<out ConnectingEncasementModelBlock> = CODEC

	override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
		super.createBlockStateDefinition(builder)
		builder.add(FORMED)
		for (property in FACES.values) {
			builder.add(property)
		}
	}

	/** What one side of this casing currently shows toward its neighbor - see the class KDoc. */
	enum class FaceMode : StringRepresentable {
		NONE, ARM, CAP;

		override fun getSerializedName(): String = name.lowercase(Locale.ROOT)
	}

	companion object {
		/** See the class KDoc - mirrored from the synced flag on this member's own encasement state, so the client can gate seam fillers without re-deriving cluster shape. */
		val FORMED: BooleanProperty = BooleanProperty.create("formed")

		/** One [FaceMode] per face direction, named after its direction (`north`..`down`) like the connection bits on [PipeBlock]. */
		val FACES: Map<Direction, EnumProperty<FaceMode>> =
			Direction.entries.associateWith { EnumProperty.create(it.name.lowercase(Locale.ROOT), FaceMode::class.java) }

		val CODEC: MapCodec<ConnectingEncasementModelBlock> = simpleCodec(::ConnectingEncasementModelBlock)
	}
}
