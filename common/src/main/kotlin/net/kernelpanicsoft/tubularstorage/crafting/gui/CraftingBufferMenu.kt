package net.kernelpanicsoft.tubularstorage.crafting.gui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.tubularstorage.crafting.CraftingBufferEncasementState
import net.kernelpanicsoft.tubularstorage.crafting.CraftingCpuManager
import net.kernelpanicsoft.tubularstorage.crafting.craftingBufferAt
import net.kernelpanicsoft.tubularstorage.network.CancelCraftingBufferJobPacket
import net.kernelpanicsoft.tubularstorage.network.CraftingBufferActiveJobView
import net.kernelpanicsoft.tubularstorage.network.CraftingBufferBacklogEntryView
import net.kernelpanicsoft.tubularstorage.network.CraftingBufferStatusPacket
import net.kernelpanicsoft.tubularstorage.network.RequestCraftingBufferStatusPacket
import net.kernelpanicsoft.tubularstorage.network.TubularStorageNetworkChannel
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.tubularstorage.registry.GuiRegistry
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory

/**
 * Shows one Crafting CPU member's own local buffer slots ([CraftingBufferEncasementState.localStorage])
 * alongside this member's own cluster's currently active job and backlog. The job/backlog live only
 * on the cluster's own leader ([CraftingCpuManager.Cluster.leader]) - see
 * [CraftingBufferEncasementState]'s own KDoc - so [sendStatus]/[cancelJob] always resolve [tile]'s
 * own cluster first rather than reading [tile] directly, regardless of which member was actually
 * opened.
 */
class CraftingBufferMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity) :
	ComposeBlockContainerMenu<MultipartBlockEntity, CraftingBufferMenu>(GuiRegistry.CraftingBuffer, id, inventory, tile) {

	override fun registerSlotHandlers() {
		val state = tile.encasement.value as? CraftingBufferEncasementState ?: return
		handler("buffer", state.localStorage)
	}

	/** This cluster's own currently active job, `null` if idle - Compose state, so [CraftingBufferScreen] recomposes whenever [applyStatus] applies a fresh [CraftingBufferStatusPacket]. */
	var activeJob: CraftingBufferActiveJobView? by mutableStateOf(null)
		private set

	/** This cluster's own currently queued jobs, in order - see [activeJob]. */
	var backlog: List<CraftingBufferBacklogEntryView> by mutableStateOf(emptyList())
		private set

	/** See [AbstractTerminalHookMenu.onMenuOpened][net.kernelpanicsoft.tubularstorage.pipe.gui.AbstractTerminalHookMenu.onMenuOpened]'s identical note - requested from the client's own `onMenuOpened` rather than pushed eagerly server-side, so it can't lose the race against the client not yet being the active `containerMenu`. */
	override fun onMenuOpened() {
		super.onMenuOpened()
		if (level.isClientSide) TubularStorageNetworkChannel.toServer(RequestCraftingBufferStatusPacket)
	}

	/** Client-side: asks the server for this cluster's own current job/backlog status. */
	fun requestStatus() = TubularStorageNetworkChannel.toServer(RequestCraftingBufferStatusPacket)

	/** Client-side: applies a freshly received [CraftingBufferStatusPacket]. */
	fun applyStatus(active: CraftingBufferActiveJobView?, backlog: List<CraftingBufferBacklogEntryView>) {
		this.activeJob = active
		this.backlog = backlog
	}

	/** Server-side: computes and replies with [tile]'s own cluster's current job/backlog status - always read off the cluster's own leader, not [tile] directly (see this class's own KDoc). */
	fun sendStatus() {
		val level = level as? ServerLevel ?: return
		val leaderPos = CraftingCpuManager.get(level).clusterOf(level, tile.blockPos).leader
		val state = craftingBufferAt(level, leaderPos) ?: return

		val active = state.activeJob?.let { job ->
			CraftingBufferActiveJobView(job.id, job.target, job.targetAmount, job.delivered, job.status, job.toTree())
		}
		val backlog = state.backlog.map { CraftingBufferBacklogEntryView(it.id, it.target, it.targetAmount) }
		TubularStorageNetworkChannel.toPlayer(player as ServerPlayer, CraftingBufferStatusPacket(active, backlog))
	}

	/** Client-side: cancels [jobId]'s own job - see [CancelCraftingBufferJobPacket]. */
	fun requestCancel(jobId: String) = TubularStorageNetworkChannel.toServer(CancelCraftingBufferJobPacket(jobId))

	/** Server-side: cancels [jobId]'s own job on [tile]'s own cluster - see [CraftingBufferEncasementState.cancelJob]. */
	fun cancelJob(jobId: String) {
		val level = level as? ServerLevel ?: return
		val leaderPos = CraftingCpuManager.get(level).clusterOf(level, tile.blockPos).leader
		craftingBufferAt(level, leaderPos)?.cancelJob(jobId)
	}
}
