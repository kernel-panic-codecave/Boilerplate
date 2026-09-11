package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.*
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.Slots
import net.kernelpanicsoft.archie.gui.composables.basic.Label
import net.kernelpanicsoft.archie.gui.composables.containers.ContainerPanel
import net.kernelpanicsoft.archie.gui.composables.input.RadioGroup
import net.kernelpanicsoft.archie.gui.composables.input.RadioOption
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.boilerplate.client.BoilerplateTheme
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.network.UpdateExtractionConfigPacket
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.kernelpanicsoft.boilerplate.pipe.hook.ExtractionDistribution
import net.kernelpanicsoft.boilerplate.pipe.hook.ExtractionHookState
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * Configuration for the extraction hook on [ExtractionHookMenu.direction]'s face: what it may pull,
 * where it sends it, how often, how much, and how much of it may queue at the far end.
 *
 * Speed and amount are settable here for now and are the two an upgrade system will eventually own
 * instead - which is why they read as plain numbers rather than tiers.
 *
 * Every control writes to local Compose state immediately and pushes the whole configuration in one
 * [UpdateExtractionConfigPacket]; the filter card is a real vanilla slot
 * ([ExtractionHookMenu.registerSlotHandlers]) and carries itself.
 */
class ExtractionHookScreen(private val menu: ExtractionHookMenu, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<ExtractionHookMenu>(menu, playerInventory, title) {

	private val contentWidth = 18 * 9

	init {
		start { content() }
	}

	@Composable
	fun content() {
		var filterMode by remember { mutableStateOf(menu.filterMode()) }
		var distribution by remember { mutableStateOf(menu.distribution()) }
		var interval by remember { mutableStateOf(menu.intervalTicks()) }
		var amount by remember { mutableStateOf(menu.amount()) }
		var queueWholes by remember { mutableStateOf(menu.queueWholes()) }

		fun push() {
			BoilerplateNetworkChannel.toServer(
				UpdateExtractionConfigPacket(menu.pos, menu.direction, filterMode, distribution, interval, amount, queueWholes),
			)
		}

		BoilerplateTheme {
			ContainerPanel(contentWidth = contentWidth) {
				Column(verticalArrangement = Arrangement.spacedBy(6)) {
					// An empty card here means "pull anything", not "pull nothing" - see
					// ExtractionHookState.accepts for why this differs from a sorting hook's.
					Label(Component.literal("Filter"))
					Slots("filter")

					RadioGroup(
						options = listOf(
							RadioOption(FilterMode.WHITELIST, Component.literal("Whitelist")),
							RadioOption(FilterMode.BLACKLIST, Component.literal("Blacklist")),
						),
						selected = filterMode,
						onSelected = { filterMode = it; push() },
					)

					Label(Component.literal("Destinations"))
					RadioGroup(
						options = listOf(
							RadioOption(ExtractionDistribution.ROUND_ROBIN, Component.literal("Round robin")),
							RadioOption(ExtractionDistribution.NEAREST_FIRST, Component.literal("Nearest first")),
						),
						selected = distribution,
						onSelected = { distribution = it; push() },
					)

					// Ticks between pulls, so the number goes the opposite way to the speed it
					// describes - said in the caption rather than inverted into a "speed" the hook
					// would then have to convert back.
					NumberField(
						amount = interval.toLong(),
						range = 1L..ExtractionHookState.MAX_INTERVAL_TICKS.toLong(),
						onAmountChange = { interval = it.toInt(); push() },
						leading = { Label(Component.literal("Every:")) },
						trailing = { Label(Component.literal("ticks")) },
					)

					// Zero is a real setting rather than an empty field: it means one whole batch of
					// whatever kind is being pulled, which is a per-kind quantity this screen has no
					// way to name for itself.
					NumberField(
						amount = amount,
						range = ExtractionHookState.KIND_DEFAULT_AMOUNT..menu.maxAmount(),
						onAmountChange = { amount = it; push() },
						leading = { Label(Component.literal("Amount:")) },
						trailing = {
							if (amount <= ExtractionHookState.KIND_DEFAULT_AMOUNT) Label(Component.literal("(one batch)"))
						},
					)

					// How much may be waiting in the pipes at the destination on top of what it can
					// hold - the buffer that stops a machine at the end of a long run idling for the
					// length of that run every time it empties a slot. In whole units of whatever is
					// being pulled, since one hook may pull either.
					NumberField(
						amount = queueWholes.toLong(),
						range = 0L..ExtractionHookState.MAX_QUEUE_WHOLES.toLong(),
						onAmountChange = { queueWholes = it.toInt(); push() },
						leading = { Label(Component.literal("Queue:")) },
						trailing = {
							Label(Component.literal(if (queueWholes <= 0) "(none)" else "stacks / buckets"))
						},
					)
				}
			}
		}
	}
}
