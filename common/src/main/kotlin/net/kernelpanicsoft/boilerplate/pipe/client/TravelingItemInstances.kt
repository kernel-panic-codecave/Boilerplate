package net.kernelpanicsoft.boilerplate.pipe.client

import net.kernelpanicsoft.boilerplate.config.BoilerplateConfig
import com.mojang.math.Axis
import dev.engine_room.flywheel.api.instance.Instance
import dev.engine_room.flywheel.api.model.Mesh
import dev.engine_room.flywheel.api.visualization.VisualizationContext
import dev.engine_room.flywheel.lib.instance.InstanceTypes
import dev.engine_room.flywheel.lib.instance.TransformedInstance
import net.kernelpanicsoft.boilerplate.client.WorldMeshMotion
import net.kernelpanicsoft.boilerplate.client.InstancedMeshes
import kotlin.math.sin
import kotlin.math.PI
import earth.terrarium.common_storage_lib.resources.ResourceComponent
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
 * Instances are reconciled against the cargo rather than rebuilt from it: the transform moves every
 * frame because the cargo is moving, but only a slot whose *geometry* changed is re-instanced. A
 * busy pipe changes its contents almost every frame, and deleting and recreating the whole set each
 * time is the immediate-mode cost instancing exists to avoid - every recreated instance is a fresh
 * GPU allocation, and Flywheel re-sorts its entire draw list whenever an instancer comes or goes.
 *
 * The geometry itself is shared through [InstancedMeshes.modelOf], so every droplet of one fluid in
 * the whole level lands in a single instancer and a single draw call.
 *
 * Position is deliberately taken from
 * [net.kernelpanicsoft.boilerplate.pipe.client.PipeContentsClientCache]'s dead-reckoned copy by the
 * caller, so a segment interpolates between syncs rather than stepping.
 */
class TravelingItemInstances(
	private val context: VisualizationContext,
	/** The pipe's real world position - what the path's own absolute positions are compared against. */
	private val pos: BlockPos,
	/** The same position relative to Flywheel's current render origin - where instances actually go. */
	private val visualPos: BlockPos,
) {
	private val instances = ArrayList<TransformedInstance>()

	/** The mesh each entry of [instances] was created for, so [reconcile] can tell an unchanged slot from one that needs new geometry. */
	private val instanceMeshes = ArrayList<Mesh>()

	/** Every instance currently alive, for the owning visual's own `relight`. */
	val active: List<TransformedInstance> get() = instances

	/**
	 * Reconciles instances against the cargo, then places each one along its own leg of the pipe.
	 *
	 * [gameTime] is the render thread's fractional game time, driving the tumble - so every piece of
	 * cargo turns in step rather than each carrying its own animation state.
	 */
	fun update(items: List<TravelingItem>, gameTime: Float) {
		// One pass, keeping each item's mesh: `meshFor` is a registry lookup and a cache probe per
		// item, and a filter that calls it only to discard the answer pays for it twice.
		val drawable = ArrayList<TravelingItem>(items.size)
		val meshes = ArrayList<Mesh>(items.size)
		for (item in items) {
			if (!item.shouldDraw()) continue
			val mesh = meshFor(item) ?: continue
			drawable += item
			meshes += mesh
		}
		reconcile(meshes)

		for ((index, item) in drawable.withIndex()) {
			val at = positionOf(item)
			instances[index].apply {
				setIdentityTransform()
				translate(visualPos.x.toFloat(), visualPos.y.toFloat(), visualPos.z.toFloat())
				translate(at.x.toFloat(), at.y.toFloat(), at.z.toFloat())
				rotate(Axis.YP.rotationDegrees(gameTime * BoilerplateConfig.Visuals.TravelingResources.spinDegreesPerTick))
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
						1f + BoilerplateConfig.Visuals.TravelingResources.wobbleAmplitude * sin(wobble),
						1f + BoilerplateConfig.Visuals.TravelingResources.wobbleAmplitude * sin(wobble + 2f * PI.toFloat() / 3f),
						1f + BoilerplateConfig.Visuals.TravelingResources.wobbleAmplitude * sin(wobble + 4f * PI.toFloat() / 3f),
					)
				}
				setChanged()
			}
		}
	}

	fun delete() {
		instances.forEach(Instance::delete)
		instances.clear()
		instanceMeshes.clear()
	}

	/**
	 * Brings [instances] in line with [meshes] slot by slot, touching only what changed.
	 *
	 * A slot already holding an instance of the same mesh is left exactly as it is - cargo shuffling
	 * along a pipe of one fluid never re-instances anything, and a mixed pipe re-instances only the
	 * slots whose resource actually differs. Surplus instances are dropped from the tail.
	 */
	private fun reconcile(meshes: List<Mesh>) {
		for (index in meshes.indices) {
			val mesh = meshes[index]
			when {
				index >= instances.size -> {
					instances += instanceFor(mesh)
					instanceMeshes += mesh
				}
				instanceMeshes[index] !== mesh -> {
					instances[index].delete()
					instances[index] = instanceFor(mesh)
					instanceMeshes[index] = mesh
				}
			}
		}
		while (instances.size > meshes.size) {
			instances.removeAt(instances.lastIndex).delete()
			instanceMeshes.removeAt(instanceMeshes.lastIndex)
		}
	}

	/** A new instance of [mesh]'s shared model - shared so that every droplet of one fluid draws from one instancer. */
	private fun instanceFor(mesh: Mesh): TransformedInstance =
		context.instancerProvider().instancer(InstanceTypes.TRANSFORMED, InstancedMeshes.modelOf(mesh)).createInstance()

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

		/** How fast the wobble cycles. Deliberately not a whole fraction of [BoilerplateConfig.Visuals.TravelingResources.spinDegreesPerTick]'s own period, so the squash never lands on the same face twice running. */
		private const val WOBBLE_RADIANS_PER_TICK = 0.55f

		/** How far apart two droplets in one segment are pushed in the cycle, so they do not pulse in unison. */
		private const val WOBBLE_PHASE_OFFSET = 1.7f

		/** The two extra tumble axes, in degrees per tick. Deliberately unrelated to each other and to [BoilerplateConfig.Visuals.TravelingResources.spinDegreesPerTick], so the three together never repeat on any short period. */
		private const val TUMBLE_X_DEGREES_PER_TICK = 2.7f
		private const val TUMBLE_Z_DEGREES_PER_TICK = 1.9f

		/** How far apart two droplets in one segment are pushed in the tumble, in degrees. */
		private const val TUMBLE_PHASE_OFFSET = 47f
	}
}
