package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.Panel
import net.kernelpanicsoft.archie.gui.composables.input.Button
import net.kernelpanicsoft.archie.gui.layer.LayerStackManager
import net.kernelpanicsoft.archie.gui.layout.Alignment
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.position.padding
import net.kernelpanicsoft.archie.gui.modifiers.sizeIn
import net.kernelpanicsoft.archie.gui.theme.LocalTheme
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.kernelpanicsoft.boilerplate.resource.displayName
import net.minecraft.network.chat.Component

/**
 * Pushes a modal onto [this] asking for an exact amount of [resource], seeded with [authored] and
 * capped at [max] - the typed counterpart to scrolling a cell's amount one notch at a time, opened
 * by middle-clicking the cell (see [ResourceGhostSlot]).
 *
 * Reads, edits and hands back **authored** amounts (items, or millibuckets for a fluid, or whatever
 * unit an addon kind authors in), never the platform count - so a caller holding authored cells
 * passes and stores them unchanged, and one holding platform amounts converts on both sides. See
 * [net.kernelpanicsoft.boilerplate.resource.ResourceKind.toAuthored].
 *
 * A scroll notch is the floor as well as the step: an amount below one notch is not something the
 * scroll gesture can express either, and for a fluid it means a cell of 1mB that looks set and
 * moves nothing. The modal dismisses itself either way.
 *
 * @param onConfirm Invoked with the chosen authored amount, already clamped.
 */
fun LayerStackManager.setAmountDialog(
	resource: ResourceComponent,
	authored: Long,
	max: Long,
	onConfirm: (Long) -> Unit,
) {
	modal {
		SetAmountDialogContent(
			resource = resource,
			initial = authored,
			max = max,
			onConfirm = { amount -> onConfirm(amount); dismiss() },
			onCancel = { dismiss() },
		)
	}
}

@Composable
private fun SetAmountDialogContent(
	resource: ResourceComponent,
	initial: Long,
	max: Long,
	onConfirm: (Long) -> Unit,
	onCancel: () -> Unit,
) {
	val kind = ResourceKindRegistry.forResource(resource)
	val step = kind?.authoredStep ?: 1L
	val ceiling = max.coerceAtLeast(step)
	var amount by remember(resource, initial) { mutableStateOf(initial.coerceIn(step, ceiling)) }

	Panel(modifier = Modifier.sizeIn(minWidth = 150), contentAlignment = Alignment.Center) {
		Column(verticalArrangement = Arrangement.spacedBy(4), horizontalAlignment = Alignment.CenterHorizontally) {
			Row(horizontalArrangement = Arrangement.spacedBy(4), verticalAlignment = Alignment.CenterVertically) {
				// The preview draws from the platform count, so the live authored amount converts
				// on the way in - which is what keeps its corner label tracking the field.
				ResourceFakeSlot(resource, kind?.toPlatform(amount) ?: amount)
				Text(resource.displayName(), dropShadow = false, color = LocalTheme.current.darkTextColor)
			}
			Text(Component.literal("Amount (max $ceiling):"), dropShadow = false, color = LocalTheme.current.darkTextColor)
			QuantityStepper(
				amount = amount,
				range = step..ceiling,
				onAmountChange = { amount = it },
				steps = stepperStepsFor(kind),
			)
			Row(
				horizontalArrangement = Arrangement.spacedBy(4),
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier.padding(top = 4),
			) {
				Button(onClick = { onCancel() }) { Text(Component.literal("Cancel"), dropShadow = false) }
				Button(onClick = { onConfirm(amount) }) { Text(Component.literal("Set"), dropShadow = false) }
			}
		}
	}
}
