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
 * [EncasementModelBlock] subclass carrying [CraftingBufferEncasementType]'s own variant properties.
 * Lives as its own class rather than a shared `EncasementModelBlock` property set because a
 * blockstate property shows up in every state of every instance of the class; other encasement
 * kinds shouldn't inherit crafting-specific ones.
 *
 * - each face's own [FACES] mode answers what that side shows toward its neighbor:
 *   [FaceMode.ARM] where another crafting buffer sits (the arm bridging the pair), [FaceMode.CAP]
 *   where the segment carries a pipe that ends here unconnected (the cap plugging the casing's pipe
 *   hole), and [FaceMode.NONE] where the opening is left as-is (the pipe passes through, a hook
 *   covers it, or there is nothing to plug at all).
 *
 * - [FORMED] mirrors the synced
 *   [net.kernelpanicsoft.tubularstorage.crafting.CraftingBufferEncasementState.formed] flag - the
 *   blockstate definition gates its edge/corner pieces on it, so only a cluster whose members fill
 *   their bounding box exactly renders the seam fillers between adjacent arms.
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
