package net.kernelpanicsoft.tubularstorage.crafting.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import earth.terrarium.common_storage_lib.resources.ResourceStack
import kotlinx.coroutines.delay
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.Slots
import net.kernelpanicsoft.archie.gui.composables.basic.ProgressBar
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.ContainerPanel
import net.kernelpanicsoft.archie.gui.composables.containers.Panel
import net.kernelpanicsoft.archie.gui.composables.containers.Scrollable
import net.kernelpanicsoft.archie.gui.composables.input.Button
import net.kernelpanicsoft.archie.gui.layout.Alignment
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.height
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.archie.gui.theme.LocalTheme
import net.kernelpanicsoft.archie.gui.theme.Theme
import net.kernelpanicsoft.tubularstorage.network.CraftingBufferActiveJobView
import net.kernelpanicsoft.tubularstorage.network.CraftingBufferBacklogEntryView
import net.kernelpanicsoft.tubularstorage.pipe.gui.CraftingTreeView
import net.kernelpanicsoft.tubularstorage.pipe.gui.FakeSlot
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import kotlin.time.Duration.Companion.milliseconds

/**
 * The cluster's own currently active job (with a Cancel button and, once its steps have started, a
 * [CraftingTreeView] of its own progress) and backlog (a scrollable list, each entry showing its own
 * queue position and a Cancel button), alongside this member's own local buffer slots
 * ([CraftingBufferMenu.registerSlotHandlers]). Polls [CraftingBufferMenu.requestStatus] on the same
 * cadence as [net.kernelpanicsoft.tubularstorage.pipe.gui.AbstractTerminalHookScreen]'s own Jobs tab.
 */
class CraftingBufferScreen(private val menu: CraftingBufferMenu, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<CraftingBufferMenu>(menu, playerInventory, title) {

	init {
		start { content() }
	}

	@Composable
	fun content() {
		LaunchedEffect(Unit) {
			while (true) {
				menu.requestStatus()
				delay(STATUS_POLL_MILLIS.milliseconds)
			}
		}
		Theme {
			ContainerPanel(contentWidth = CONTENT_WIDTH) {
				Row(horizontalArrangement = Arrangement.spacedBy(6), verticalAlignment = Alignment.Top) {
					Column(verticalArrangement = Arrangement.spacedBy(4)) {
						Text(Component.literal("Local buffer"), dropShadow = false)
						Slots("buffer", 3, 3)
					}
					Column(verticalArrangement = Arrangement.spacedBy(6), modifier = Modifier.width(SIDE_COLUMN_WIDTH)) {
						ActiveJobPanel(menu.activeJob, onCancel = menu::requestCancel)
						BacklogPanel(menu.backlog, height = BACKLOG_HEIGHT, onCancel = menu::requestCancel)
					}
				}
			}
		}
	}

	companion object {
		private const val SIDE_COLUMN_WIDTH = 180
		private const val CONTENT_WIDTH = 54 + 6 + SIDE_COLUMN_WIDTH
		private const val BACKLOG_HEIGHT = 72
		private const val STATUS_POLL_MILLIS = 250L
	}
}

@Composable
private fun ActiveJobPanel(job: CraftingBufferActiveJobView?, onCancel: (String) -> Unit) {
	Panel(variant = "inset") {
		Column(verticalArrangement = Arrangement.spacedBy(4)) {
			Text(Component.literal("Active job"), dropShadow = false)
			if (job == null) {
				Text(Component.literal("Idle"), dropShadow = false, color = LocalTheme.current.darkTextColor)
			} else {
				Row(horizontalArrangement = Arrangement.spacedBy(4), verticalAlignment = Alignment.CenterVertically) {
					FakeSlot(ResourceStack(job.resource, job.targetAmount), isHovered = false)
					Column {
						Text(job.resource.cachedStack.hoverName, dropShadow = false)
						Text(Component.literal(job.status), dropShadow = false)
					}
				}
				ProgressBar(
					progress = if (job.targetAmount > 0) (job.delivered.toFloat() / job.targetAmount).coerceIn(0f, 1f) else 0f,
					modifier = Modifier.width(120),
				)
				job.tree?.let { CraftingTreeView(roots = listOf(it), modifier = Modifier.height(100)) }
				Button(onClick = { onCancel(job.id) }) { Text(Component.literal("Cancel"), dropShadow = false) }
			}
		}
	}
}

@Composable
private fun BacklogPanel(backlog: List<CraftingBufferBacklogEntryView>, height: Int, onCancel: (String) -> Unit) {
	Panel(variant = "inset") {
		Column(verticalArrangement = Arrangement.spacedBy(4)) {
			Text(Component.literal("Queued (${backlog.size})"), dropShadow = false)
			if (backlog.isEmpty()) {
				Text(Component.literal("Nothing queued"), dropShadow = false, color = LocalTheme.current.darkTextColor)
			} else {
				Scrollable(modifier = Modifier.height(height)) {
					Column(verticalArrangement = Arrangement.spacedBy(2)) {
						for ((index, entry) in backlog.withIndex()) {
							Row(horizontalArrangement = Arrangement.spacedBy(4), verticalAlignment = Alignment.CenterVertically) {
								FakeSlot(ResourceStack(entry.resource, entry.amount), isHovered = false)
								Column {
									Text(Component.literal("Position ${index + 1} of ${backlog.size}"), dropShadow = false)
									Text(entry.resource.cachedStack.hoverName, dropShadow = false)
								}
								Button(onClick = { onCancel(entry.id) }) { Text(Component.literal("Cancel"), dropShadow = false) }
							}
						}
					}
				}
			}
		}
	}
}
