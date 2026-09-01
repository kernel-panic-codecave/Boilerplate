package net.kernelpanicsoft.boilerplate.warehouse.client

import com.mojang.blaze3d.vertex.PoseStack
import net.kernelpanicsoft.boilerplate.debug.client.DebugColor
import net.kernelpanicsoft.boilerplate.debug.client.DebugLineBatch
import net.kernelpanicsoft.boilerplate.network.WarehouseDebugSnapshotPacket.JobKind
import net.kernelpanicsoft.boilerplate.network.WarehouseDebugSnapshotPacket.JobStage
import net.kernelpanicsoft.boilerplate.warehouse.GantryClientCache
import net.kernelpanicsoft.boilerplate.warehouse.GantryVisualState
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

/**
 * Draws the warehouse overlay into the world frame: every bound warehouse's volume, the racks its
 * index actually found (told apart by whether they hold anything and whether put-away can currently
 * reach them), the crane's own queued and in-flight work as source -> destination hops, the path
 * the head is presently following, and the head itself tinted by what the controller is doing.
 *
 * That last part is the warehouse's *status* indicator, which used to be a coloured cage drawn
 * around the head unconditionally by both
 * [WarehouseControllerBlockEntityRenderer] and its Flywheel twin [WarehouseControllerVisual] - two
 * copies of one [GantryVisualState] -> colour mapping that had to be kept in step by hand. It lives
 * here now, once, for both render paths: the overlay draws over whichever of the two actually drew
 * the gantry, so the indicator no longer depends on which is in use. The cost is that it is now
 * behind the debug toggle with everything else rather than always on.
 *
 * Everything except the head and its path is baked once per snapshot into a [DebugLineBatch] - the
 * server sends one every 10 ticks and a mature warehouse runs to thousands of racks, so rebuilding
 * that per frame would be the same mistake
 * [net.kernelpanicsoft.boilerplate.pipe.client.DebugNetworkRenderer] used to make. The head and
 * path are the exception on purpose: they are dead-reckoned from [GantryClientCache] and so genuinely
 * differ every frame, but they are a handful of segments.
 *
 * Camera-relative and delta-free, drawn under the frame's rotation-only pose stack exactly like
 * vanilla's hit outline, then flushed as its own `RenderType.lines()` batch.
 */
object WarehouseDebugRenderer {
	/** The [WarehouseDebugCache.snapshot] list the [staticLines] batch was baked from - a fresh snapshot replaces the list instance, so identity is the invalidation signal. */
	private var bakedKey: List<WarehouseDebugCache.Warehouse>? = null

	/** Everything that only changes when a snapshot lands: volumes, racks, job hops. */
	private val staticLines = DebugLineBatch()

	/** The head cage and its remaining path, rebuilt every frame because both are dead-reckoned. */
	private val dynamicLines = DebugLineBatch()

	private val BOUNDS_COLOR = DebugColor.rgb(0x6f7d8c, 0.5f)
	private val BOUNDS_RESCAN_COLOR = DebugColor.rgb(0xffcc00, 0.75f)
	private val CONTROLLER_COLOR = DebugColor.rgb(0xff8c1a, 0.9f)

	/** A rack holding something - the more distinct resources it holds, the warmer, so a mixed rack reads differently from a single-item one at a glance. */
	private val RACK_STORED_COLOR = DebugColor.rgb(0x37e06e, 0.7f)
	private val RACK_MIXED_COLOR = DebugColor.rgb(0xc8e037, 0.7f)

	/** A known-but-empty rack that put-away can reach. */
	private val RACK_AVAILABLE_COLOR = DebugColor.rgb(0x37b0e0, 0.6f)

	/** A rack the index knows about but that is *not* in `availableSlots` - the shape a "put-away is ignoring this chest" bug takes. */
	private val RACK_UNREACHABLE_COLOR = DebugColor.rgb(0xe8432e, 0.7f)

	private val JOB_COLORS = mapOf(
		JobKind.RETRIEVE to DebugColor.rgb(0xff9f1c),
		JobKind.STOW to DebugColor.rgb(0x4d9bff),
		JobKind.MOVE to DebugColor.rgb(0xb56cff),
	)

	/** How opaque a job hop is drawn, by how far along it is - work in hand reads strongest, the housekeeping backlog faintest. */
	private val STAGE_ALPHA = mapOf(
		JobStage.CARRIED to 0.95f,
		JobStage.FETCHING to 0.75f,
		JobStage.PENDING to 0.45f,
		JobStage.DEFRAG to 0.25f,
	)

	private val PATH_COLOR = DebugColor.rgb(0xffd91a, 0.95f)

	private val HEAD_COLORS = mapOf(
		GantryVisualState.INDEXING to DebugColor.rgb(0xffcc00),
		GantryVisualState.MOVING to DebugColor.rgb(0x33ccff),
		GantryVisualState.IDLE to DebugColor.rgb(0x8a8a8a, 0.5f),
	)

	/** Half the head model's own extent (it spans `[3,3,3]`..`[13,13,13]`), plus a hair, so the cage sits just proud of the head's real surface rather than around a full block - see [WarehouseControllerBlockEntityRenderer]'s own note on sizing to the model. */
	private const val HEAD_RADIUS = 5.0 / 16.0 + 0.02

	/** Racks are drawn slightly inside their block so a rack sitting flush against the volume's own wall doesn't z-fight the bounds box. */
	private const val RACK_INSET = 0.08

	private const val CONTROLLER_INSET = 0.15

	fun renderFrame(poseStack: PoseStack, bufferSource: MultiBufferSource.BufferSource) {
		val minecraft = Minecraft.getInstance()
		val level = minecraft.level
		WarehouseDebugCache.onLevel(level)
		if (level == null) return

		val warehouses = WarehouseDebugCache.snapshot()
		if (warehouses.isEmpty()) return

		bakeStatic(warehouses)

		val gameTime = level.gameTime + minecraft.timer.getGameTimeDeltaPartialTick(false).toDouble()
		dynamicLines.clear()
		for (warehouse in warehouses) {
			bakeDynamic(warehouse, gameTime)
		}

		val cam = minecraft.gameRenderer.mainCamera.position
		val consumer = bufferSource.getBuffer(RenderType.lines())
		val pose = poseStack.last()
		staticLines.draw(consumer, pose, cam)
		dynamicLines.draw(consumer, pose, cam)
		bufferSource.endBatch(RenderType.lines())
	}

	/** Rebuilds [staticLines] only when a fresh snapshot replaces the warehouse list (see [bakedKey]). */
	private fun bakeStatic(warehouses: List<WarehouseDebugCache.Warehouse>) {
		if (warehouses === bakedKey) return
		bakedKey = warehouses

		staticLines.clear()
		for (warehouse in warehouses) {
			staticLines.addBox(
				warehouse.min.x.toDouble(), warehouse.min.y.toDouble(), warehouse.min.z.toDouble(),
				warehouse.max.x + 1.0, warehouse.max.y + 1.0, warehouse.max.z + 1.0,
				if (warehouse.rescanning) BOUNDS_RESCAN_COLOR else BOUNDS_COLOR,
			)
			blockBox(warehouse.controller, CONTROLLER_INSET, CONTROLLER_COLOR)

			for (rack in warehouse.racks) {
				blockBox(rack.pos, RACK_INSET, rackColor(rack))
			}
			for (job in warehouse.jobs) {
				val color = JOB_COLORS[JobKind.entries.getOrNull(job.kind) ?: JobKind.RETRIEVE] ?: continue
				val alpha = STAGE_ALPHA[JobStage.entries.getOrNull(job.stage) ?: JobStage.PENDING] ?: continue
				staticLines.add(Vec3.atCenterOf(job.from), Vec3.atCenterOf(job.to), color.alpha(alpha))
			}
		}
	}

	/**
	 * The head cage and the path it is still following, for one warehouse.
	 *
	 * Position comes from [GantryClientCache] rather than the snapshot's own [WarehouseDebugCache.Warehouse.headPos]
	 * wherever there is an entry, so the cage tracks the same smoothly dead-reckoned head the gantry
	 * renderer draws instead of stepping once per snapshot. The snapshot's copy is the fallback for
	 * a controller that has never synced one - a freshly bound, never-yet-run gantry - which is the
	 * same fallback [WarehouseControllerBlockEntityRenderer] makes.
	 */
	private fun bakeDynamic(warehouse: WarehouseDebugCache.Warehouse, gameTime: Double) {
		val dead = GantryClientCache.get(warehouse.controller, gameTime)
		val head = dead?.pos ?: warehouse.headPos
		val path = dead?.remainingPath ?: warehouse.path

		dynamicLines.addCube(head, HEAD_RADIUS, HEAD_COLORS.getValue(warehouse.visualState))

		var previous = head
		for (waypoint in path) {
			dynamicLines.add(previous, waypoint, PATH_COLOR)
			previous = waypoint
		}
	}

	/**
	 * A rack's colour, which is the whole reason racks are drawn individually: an indexed rack the
	 * put-away search cannot currently reach ([WarehouseDebugCache.Rack.available] false while it
	 * holds nothing) is indistinguishable from a working empty one in-game, and is exactly what a
	 * stale `availableSlots` looks like.
	 */
	private fun rackColor(rack: WarehouseDebugCache.Rack): DebugColor = when {
		rack.stored > 0L && rack.distinctResources > 1 -> RACK_MIXED_COLOR
		rack.stored > 0L -> RACK_STORED_COLOR
		rack.available -> RACK_AVAILABLE_COLOR
		else -> RACK_UNREACHABLE_COLOR
	}

	private fun blockBox(pos: BlockPos, inset: Double, color: DebugColor) = staticLines.addBox(
		pos.x + inset, pos.y + inset, pos.z + inset,
		pos.x + 1.0 - inset, pos.y + 1.0 - inset, pos.z + 1.0 - inset,
		color,
	)
}
