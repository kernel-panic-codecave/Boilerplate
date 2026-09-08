package net.kernelpanicsoft.boilerplate.pipe.client

import com.mojang.math.Axis
import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.model.Mesh
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import net.kernelpanicsoft.boilerplate.client.WorldMeshMotion
import net.kernelpanicsoft.boilerplate.client.preferredMaterial
import kotlin.math.sin
import kotlin.math.PI
import dev.engine_room.flywheel.lib.model.SingleMeshModel
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.boilerplate.network.ResourceIdentity
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3

/**
 * The instanced cargo inside one pipe segment: every [TravelingItem] it currently holds, drawn as a
 * small tumbling solid moving from the face it entered through to the face it is headed to next.
 *
 * Shared by whichever visual owns a pipe that shows its contents
 * ([net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock.showsTravelingItems]) -
 * [GlassPipeVisual] for a plain glass pipe and [MultipartBlockEntityVisual] for one promoted to a
 * multipart. Held as a component of those visuals rather than being a visual itself, because a
 * pipe's contents and its body are the same block entity and Flywheel allows one visual per.
 *
 * A [TravelingItem] is an envelope around any [ResourceComponent], and one pipe carries every
 * registered [net.kernelpanicsoft.boilerplate.pipe.network.PipeCarriage.PRIMARY] network type at
 * once, so the geometry comes from the resource's own kind
 * ([net.kernelpanicsoft.boilerplate.client.ResourceDisplayKind.worldMesh]) - an item's baked model,
 * a fluid's droplet, whatever an addon's kind bakes. A kind with no mesh is simply not drawn.
 *
 * Instances are rebuilt only when the *set* of cargo changes (by resource identity, in order), not
 * every frame: the transform moves every frame because the cargo is moving, but re-instancing a
 * mesh per frame would be the immediate-mode cost instancing exists to avoid. Position is
 * deliberately taken from [net.kernelpanicsoft.boilerplate.pipe.client.PipeContentsClientCache]'s
 * dead-reckoned copy by the caller, so a segment interpolates between syncs rather than stepping.
 */
class TravelingItemInstances(
	private val context: VisualizationContext,
	/** The pipe's real world position - what the path's own absolute positions are compared against. */
	private val pos: BlockPos,
	/** The same position relative to Flywheel's current render origin - where instances actually go. */
	private val visualPos: BlockPos,
) {
	private var instances: List<TransformedInstance> = emptyList()

	/** What [instances] was built for - the cargo's resources in order, so a change of contents (not merely of position) is what triggers a rebuild. */
	private var signature: List<ResourceIdentity> = emptyList()

	/** Every instance currently alive, for the owning visual's own `relight`. */
	val active: List<TransformedInstance> get() = instances

	/**
	 * Rebuilds instances if the cargo changed, then places each one along its own leg of the pipe.
	 *
	 * [gameTime] is the render thread's fractional game time, driving the tumble - so every piece of
	 * cargo turns in step rather than each carrying its own animation state.
	 */
	fun update(items: List<TravelingItem>, gameTime: Float) {
		val drawable = items.filter { it.shouldDraw() && meshFor(it) != null }
		val newSignature = drawable.map { ResourceIdentity.of(it.stack.resource as ResourceComponent) }
		if (newSignature != signature) {
			instances.forEach(Instance::delete)
			instances = drawable.map { item ->
				// The mesh picks its own material - a translucent droplet needs blending, and an
				// item's model does not. See MaterialMesh.
				val mesh = meshFor(item)!!
				val model = SingleMeshModel(mesh, mesh.preferredMaterial())
				context.instancerProvider().instancer(InstanceTypes.TRANSFORMED, model).createInstance()
			}
			signature = newSignature
		}

		for ((index, item) in drawable.withIndex()) {
			val at = positionOf(item)
			instances[index].apply {
				setIdentityTransform()
				translate(visualPos.x.toFloat(), visualPos.y.toFloat(), visualPos.z.toFloat())
				translate(at.x.toFloat(), at.y.toFloat(), at.z.toFloat())
				rotate(Axis.YP.rotationDegrees(gameTime * SPIN_DEGREES_PER_TICK))
				// A crate keeps its upright and turns about it; a droplet has no upright to keep, so
				// it goes over on every axis and sloshes with it - see WorldMeshMotion. Each extra
				// axis runs at a rate that shares no small factor with the others, so the tumble
				// never settles into repeating the same pose. Both are phased off the item's own
				// index, so two droplets sharing a segment do not move in lockstep.
				if (motionOf(item) == WorldMeshMotion.TUMBLE) {
					val phase = index * TUMBLE_PHASE_OFFSET
					rotate(Axis.XP.rotationDegrees(gameTime * TUMBLE_X_DEGREES_PER_TICK + phase))
					rotate(Axis.ZP.rotationDegrees(gameTime * TUMBLE_Z_DEGREES_PER_TICK + phase))
					val wobble = gameTime * WOBBLE_RADIANS_PER_TICK + index * WOBBLE_PHASE_OFFSET
					scale(
						1f + WOBBLE_AMPLITUDE * sin(wobble),
						1f + WOBBLE_AMPLITUDE * sin(wobble + 2f * PI.toFloat() / 3f),
						1f + WOBBLE_AMPLITUDE * sin(wobble + 4f * PI.toFloat() / 3f),
					)
				}
				setChanged()
			}
		}
	}

	fun delete() {
		instances.forEach(Instance::delete)
		instances = emptyList()
		signature = emptyList()
	}

	/** How [item]'s own kind moves while it travels - see [WorldMeshMotion]. */
	private fun motionOf(item: TravelingItem): WorldMeshMotion {
		val resource = item.stack.resource as ResourceComponent
		return ResourceKindRegistry.forResource(resource)?.display?.worldMeshMotion ?: WorldMeshMotion.SPIN
	}

	private fun meshFor(item: TravelingItem): Mesh? {
		val resource = item.stack.resource as ResourceComponent
		return ResourceKindRegistry.forResource(resource)?.display?.worldMesh(resource, item.stack.amount)
	}

	/**
	 * [PipeContentsClientCache] already hands a mid-path item to the next segment the moment its
	 * progress passes the exit face, so one still listed here past `1f` is on its *final* leg: a
	 * delivery at its destination rather than a hand-off. It holds at the face mouth until the
	 * deposit's next sync removes it, reading as "arrived, waiting on room". The mid-path case is
	 * kept out on purpose - drawing it would park a ghost on the boundary plane the neighbour is
	 * already drawing past.
	 */
	private fun TravelingItem.shouldDraw(): Boolean = progress < 1f || path.size <= 1

	private fun positionOf(item: TravelingItem): Vec3 {
		val renderProgress = if (item.progress >= 1f) 1f else item.progress
		val from = tipOf(item.fromDirection)
		val toDirection = item.path.firstOrNull()?.let { next ->
			Direction.fromDelta(next.x - pos.x, next.y - pos.y, next.z - pos.z)
		}
		val to = toDirection?.let(::tipOf) ?: CENTER
		return pathPosition(from, to, renderProgress.toDouble())
	}

	companion object {
		/**
		 * Interpolates through [CENTER] rather than straight from [from] to [to] - a pipe's model
		 * bends at a right angle through its core box (see
		 * [net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock.CORE_SHAPE]/`armShapes`), so a
		 * single straight line between two non-opposite faces cuts across the corner and pokes
		 * outside the pipe's own shape. Routing through the centre keeps both legs axis-aligned with
		 * an arm instead, matching the model - and degenerates to the same straight line for a
		 * straight-through (opposite-face) hop, since [from]/[CENTER]/[to] are already colinear
		 * then.
		 */
		private fun pathPosition(from: Vec3, to: Vec3, progress: Double): Vec3 =
			if (progress < 0.5) from.lerp(CENTER, progress * 2.0) else CENTER.lerp(to, (progress - 0.5) * 2.0)

		/** The point at the very edge of the block face in [direction], relative to the block's own local origin. */
		private fun tipOf(direction: Direction): Vec3 = CENTER.add(Vec3.atLowerCornerOf(direction.normal).scale(0.5))

		private val CENTER = Vec3(0.5, 0.5, 0.5)

		private const val SPIN_DEGREES_PER_TICK = 4f

		/** How far a droplet squashes and stretches, as a fraction of its own size - enough to read as liquid, not enough to look like it is breathing. */
		private const val WOBBLE_AMPLITUDE = 0.14f

		/** How fast the wobble cycles. Deliberately not a whole fraction of [SPIN_DEGREES_PER_TICK]'s own period, so the squash never lands on the same face twice running. */
		private const val WOBBLE_RADIANS_PER_TICK = 0.55f

		/** How far apart two droplets in one segment are pushed in the cycle, so they do not pulse in unison. */
		private const val WOBBLE_PHASE_OFFSET = 1.7f

		/** The two extra tumble axes, in degrees per tick. Deliberately unrelated to each other and to [SPIN_DEGREES_PER_TICK], so the three together never repeat on any short period. */
		private const val TUMBLE_X_DEGREES_PER_TICK = 2.7f
		private const val TUMBLE_Z_DEGREES_PER_TICK = 1.9f

		/** How far apart two droplets in one segment are pushed in the tumble, in degrees. */
		private const val TUMBLE_PHASE_OFFSET = 47f
	}
}
