package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.*
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.*
import net.kernelpanicsoft.archie.gui.composables.input.Dropdown
import net.kernelpanicsoft.archie.gui.composables.input.DropdownOption
import net.kernelpanicsoft.archie.gui.composables.modal.Wizard
import net.kernelpanicsoft.archie.gui.composables.modal.WizardValidation
import net.kernelpanicsoft.archie.gui.composables.modal.rememberWizardState
import net.kernelpanicsoft.archie.gui.layer.LayerStackManager
import net.kernelpanicsoft.archie.gui.layout.Alignment
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.height
import net.kernelpanicsoft.archie.gui.modifiers.position.padding
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.archie.gui.theme.LocalTheme
import net.kernelpanicsoft.archie.gui.util.KColor
import net.kernelpanicsoft.boilerplate.network.CraftPlanEntry
import net.kernelpanicsoft.boilerplate.network.CraftPlanPacket
import net.kernelpanicsoft.boilerplate.network.SResourceComponent
import net.kernelpanicsoft.boilerplate.network.displayName
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry

/** Ceiling on a single request, matching what the old single-page dialog allowed. */
private const val MAX_REQUEST = 6400L

private const val WIZARD_WIDTH = 210
private const val PAGE_HEIGHT = 92

/**
 * Pushes the two-step craft request flow: choose an amount, then confirm against what that amount
 * actually entails.
 *
 * Split into steps because the two questions need different information and cost different amounts
 * to answer. Picking a number wants the cheap running "how many could I make" ([CraftPreviewMenu]),
 * re-asked on every keystroke. Committing wants the real resolve ([CraftPlanMenu]) - the steps that
 * would run, what comes from stock, what is missing, and which Crafting CPU takes it - which is far
 * too expensive to recompute per keypress and is only worth showing once the amount has settled.
 *
 * The plan page runs the same [net.kernelpanicsoft.boilerplate.crafting.CraftingRequest.resolve]
 * the submission itself does, so what it shows is what will happen rather than a separate estimate
 * that could disagree.
 *
 * @param menu The open terminal-family menu, which supplies both the preview and the plan.
 * @param resource What to craft.
 */
fun <M> LayerStackManager.craftRequestWizard(menu: M, resource: ResourceComponent) where M : CraftPreviewMenu, M : CraftPlanMenu {
	modal(dismissOnClickOutside = false) {
		CraftRequestWizardContent(menu = menu, resource = resource, onDismiss = { dismiss() })
	}
}

@Composable
private fun <M> CraftRequestWizardContent(menu: M, resource: ResourceComponent, onDismiss: () -> Unit) where M : CraftPreviewMenu, M : CraftPlanMenu {
	val theme = LocalTheme.current
	val state = rememberWizardState()
	// One "unit" of whatever is being crafted - a stack for an item, a bucket for a fluid.
	val kind = ResourceKindRegistry.forResource(resource)
	var amount by remember(resource) { mutableStateOf(kind?.let { it.toPlatform(it.defaultAuthored) } ?: 1L) }
	var chosenCpu by remember(resource) { mutableStateOf<BlockPos?>(null) }

	// Cheap and continuous: what the amount page reports while the number is still moving.
	LaunchedEffect(resource, amount) { menu.requestCraftPreview(resource, amount) }
	val preview = menu.craftPreview?.takeIf { it.first == resource }?.second

	// Expensive and one-shot: asked for only on reaching the plan page, and again if the amount
	// changes after going back to edit it.
	LaunchedEffect(state.currentIndex, resource, amount) {
		if (state.currentIndex > 0) menu.requestCraftPlan(resource, amount)
	}
	// Pinned to this request: a reply for some other amount is a stale answer, not a usable one.
	val plan = menu.craftPlan?.takeIf { it.resource == resource && it.amount == amount }

	// Defaults to the lightest-loaded cluster - the same one the server would pick unprompted, so
	// leaving the dropdown alone matches the old behaviour exactly.
	LaunchedEffect(plan) {
		val cpus = plan?.cpus.orEmpty()
		if (cpus.none { it.pos == chosenCpu }) chosenCpu = cpus.minByOrNull { it.backlog }?.pos
	}

	Surface(modifier = Modifier.padding(4)) {
		Wizard(
			state = state,
			contentWidth = WIZARD_WIDTH,
			contentHeight = PAGE_HEIGHT,
			finishText = Component.literal("Craft"),
			onCancel = onDismiss,
			onFinish = {
				menu.requestCraft(resource, amount, chosenCpu)
				onDismiss()
			},
		) {
			page(
				id = "amount",
				title = Component.literal("How many?"),
				validate = {
					when {
						// Only the amount is this page's business. Whether it can actually be made is the next
						// page's entire subject, and gating the way there on the answer meant a request that
						// could not be met could never reach the table explaining why not.
						amount !in 1..MAX_REQUEST -> WizardValidation.Blocked(Component.literal("Enter 1 to $MAX_REQUEST"))
						else -> WizardValidation.Valid
					}
				},
			) {
				Column(verticalArrangement = Arrangement.spacedBy(4), horizontalAlignment = Alignment.CenterHorizontally) {
					Row(horizontalArrangement = Arrangement.spacedBy(4), verticalAlignment = Alignment.CenterVertically) {
						TerminalSlot(ResourceStack(resource, amount))
						Text(resource.displayName(), dropShadow = false, color = theme.darkTextColor)
					}
					QuantityStepper(
						amount = amount,
						range = 1..MAX_REQUEST,
						onAmountChange = { amount = it },
					)
					Text(
						when (preview) {
							null -> Component.literal("Checking...")
							else -> Component.literal("Craftable now: $preview / $amount")
						},
						dropShadow = false,
						color = theme.darkTextColor,
					)
				}
			}

			page(
				id = "plan",
				title = Component.literal("Confirm"),
				validate = {
					when {
						plan == null -> WizardValidation.Blocked(Component.literal("Planning..."))
						plan.missing.isNotEmpty() -> WizardValidation.Blocked(blockerSummary("Missing", "ingredients", plan.missing.map { it.resource }))
						plan.cyclic.isNotEmpty() -> WizardValidation.Blocked(blockerSummary("Cyclic pattern for", "patterns are cyclic", plan.cyclic))
						plan.cpus.isEmpty() -> WizardValidation.Blocked(Component.literal("No reachable Crafting CPU"))
						chosenCpu == null -> WizardValidation.Blocked(Component.literal("Choose a Crafting CPU"))
						else -> WizardValidation.Valid
					}
				},
			) {
				CraftPlanPage(plan = plan, chosenCpu = chosenCpu, onCpuChosen = { chosenCpu = it })
			}
		}
	}
}

/** The plan page's body: the steps a request would run, what it pulls from stock, and where it would run. */
@Composable
private fun CraftPlanPage(plan: CraftPlanPacket?, chosenCpu: BlockPos?, onCpuChosen: (BlockPos) -> Unit) {
	val theme = LocalTheme.current

	if (plan == null) {
		Text(Component.literal("Working out what that would take..."), dropShadow = false, color = theme.darkTextColor)
		return
	}

	Column(verticalArrangement = Arrangement.spacedBy(3)) {
		Scrollable(modifier = Modifier.width(WIZARD_WIDTH - 8).height(52)) {
			if (plan.steps.isEmpty() && plan.stockPulls.isEmpty() && plan.missing.isEmpty() && plan.cyclic.isEmpty()) {
				Text(Component.literal("Nothing to do - it is all in stock"), dropShadow = false, color = theme.darkTextColor)
			} else {
				Table(
					columns = listOf(
						TableColumn(Component.literal("Qty"), alignment = Alignment.End),
						TableColumn(Component.literal("Resource")),
						TableColumn(Component.literal("From")),
					),
				) {
					for (entry in plan.steps) planRow(entry, Component.literal("Craft"), theme.darkTextColor)
					for (entry in plan.stockPulls) planRow(entry, Component.literal("Stock"), theme.darkTextColor)
					for (entry in plan.missing) planRow(entry, Component.literal("MISSING"), theme.darkTextColor)
					for (resource in plan.cyclic) {
						row(
							{ },
							{ Text(Component.literal(resource.displayName().string), dropShadow = false, color = theme.darkTextColor) },
							{ Text(Component.literal("CYCLIC"), dropShadow = false, color = theme.darkTextColor) },
						)
					}
				}
			}
		}

		if (plan.resolvable) {
			Dropdown(
				options = plan.cpus.map { cpu ->
					DropdownOption(
						value = cpu.pos,
						label = Component.literal("CPU ${cpu.pos.x}, ${cpu.pos.y}, ${cpu.pos.z}" + if (cpu.backlog > 0) " (${cpu.backlog} queued)" else " (idle)"),
					)
				},
				selected = chosenCpu,
				onSelected = onCpuChosen,
				placeholder = Component.literal("No Crafting CPU"),
				width = WIZARD_WIDTH - 8,
				maxVisibleOptions = 4,
			)
		}
	}
}

/**
 * The one-liner shown beside the disabled button: names a lone blocker, counts several.
 *
 * A count rather than a truncated list, because this line sits beside the button with no room to
 * grow - every blocker is named in full in the plan table above it.
 */
private fun blockerSummary(singularPrefix: String, pluralNoun: String, resources: List<SResourceComponent>): Component =
	if (resources.size == 1) Component.literal("$singularPrefix ${resources.first().displayName().string}")
	else Component.literal("${resources.size} $pluralNoun")

/** One `12 | Iron Ingot | Craft` row of the plan table. */
private fun TableScope.planRow(entry: CraftPlanEntry, source: Component, color: KColor) {
	row(
		{ Text(Component.literal(entry.amount.toString()), dropShadow = false, color = color) },
		{ Text(Component.literal(entry.resource.displayName().string), dropShadow = false, color = color) },
		{ Text(source, dropShadow = false, color = color) },
	)
}
