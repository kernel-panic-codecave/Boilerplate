package net.kernelpanicsoft.boilerplate.warehouse.rack

import dev.architectury.registry.menu.ExtendedMenuProvider
import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.archie.block.entity.NBTBlockEntity
import net.kernelpanicsoft.archie.serialization.Sync
import net.kernelpanicsoft.archie.serialization.field
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardItem
import net.kernelpanicsoft.boilerplate.resource.ResourceComponentSerializer
import net.kernelpanicsoft.boilerplate.resource.ResourceKind
import net.kernelpanicsoft.boilerplate.resource.SResourceComponent
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

/**
 * The shared half of the three shared-pool racks - [DistributedMultiTankBlockEntity],
 * [DistributedMultiBufferBlockEntity] and [OmnibufferBlockEntity].
 *
 * All three are the same block with a different answer to one question: which
 * [net.kernelpanicsoft.boilerplate.resource.ResourceMeasure]s do they take? Everything else - a
 * [PooledResourceStorage] over two persisted lists, a filter card, a routing priority, a summary
 * line - is here, so the three concrete types are a capacity, a name and a predicate each.
 *
 * ### What they are for
 *
 * A process that touches a great many resources in small amounts has nowhere good to buffer. A
 * slotted rack runs out of *slots* long before it runs out of room: 1mB each of a thousand fluids
 * needs a thousand tanks, and it is not the volume that ran out. These hold one pooled capacity
 * instead - see [PooledResourceStorage] - so a bucket's worth of room is a bucket's worth however
 * finely it is divided, and the thousandth fluid costs no more to hold than the first.
 *
 * ### Priority
 *
 * [intrinsicPriority] is `0`, the same baseline a General Rack sits at, deliberately: unlike the
 * Unstackable and Bulk racks these are not specialists for a class of *resource* that ought to win
 * put-away for it - they are a large general sink, and one that outranked an ordinary rack by
 * default would quietly become the only rack a warehouse ever used. A player who wants that promotes
 * it on the slider.
 */
abstract class PooledRackBlockEntity(type: BlockEntityType<*>, pos: BlockPos, state: BlockState) :
	NBTBlockEntity(type, pos, state), RackBlockEntity, ExtendedMenuProvider {

	override val intrinsicPriority: Int = 0

	override var priority: Int
		get() = routing.priority
		set(value) {
			routing = routing.copy(priority = value)
		}

	@Sync
	override var routing: RoutingModule by field { RoutingModule(priority = intrinsicPriority) }

	override val filter: ArchieItemStorage by itemField(1, filter = { it.item is FilterCardItem })

	/** One entry per distinct resource held, kind-tagged on the wire so any registered kind persists with no shape of its own. */
	private val records: MutableList<SResourceComponent> by listField(ResourceComponentSerializer) { mutableListOf() }

	/** [records]' amounts, index-aligned, each in its own kind's platform unit. */
	private val counts: MutableList<Long> by listField(Long.serializer()) { mutableListOf() }

	/** How much this rack holds in total, in wholes - a stack of an item, a bucket of a fluid. */
	abstract val capacityWholes: Long

	/** What one whole is called here - "buckets" for a tank, "stacks" for a buffer, and neither when it holds both. */
	abstract val wholeName: String

	/** This rack's name, for [describeContents]. */
	abstract val label: String

	/** Whether this rack takes [kind] at all - the one thing that differs between the three. */
	abstract fun holds(kind: ResourceKind): Boolean

	/**
	 * The pool itself.
	 *
	 * `{ records }`/`{ counts }` are passed as providers rather than as the lists, which is load-
	 * bearing rather than stylistic - see [UncappedItemStorage]'s own KDoc for the data loss that
	 * capturing them causes.
	 */
	val storage: PooledResourceStorage = PooledResourceStorage(
		capacityWholes = { capacityWholes },
		holds = ::holds,
		resources = { records },
		amounts = { counts },
		accepts = ::acceptsByFilter,
	) { setChanged() }

	override fun describeContents(): Component {
		val used = String.format("%.1f", storage.usedWholes())
		return Component.literal("$label: $used/$capacityWholes $wholeName across ${storage.recordCount()} resources")
	}

	override fun saveExtraData(buf: FriendlyByteBuf) {
		buf.writeBlockPos(blockPos)
	}

	override fun getDisplayName(): Component = describeContents()
}
