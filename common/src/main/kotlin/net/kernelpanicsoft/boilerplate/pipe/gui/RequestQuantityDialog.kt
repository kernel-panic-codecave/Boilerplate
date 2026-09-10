package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import earth.terrarium.common_storage_lib.resources.ResourceStack
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
import net.minecraft.network.chat.Component
import kotlin.math.min
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.kernelpanicsoft.boilerplate.resource.displayName
import earth.terrarium.common_storage_lib.resources.ResourceComponent

/**
 * Pushes a modal onto [this] asking how much of [stack] to request - a digit-only quantity field,
 * live-clamped to `1..`however much this terminal can currently reach (which can exceed a single
 * stack's usual max size - see `docs/design/m3-warehouse-storage.md`), with `-`/`+` stepper buttons
 * either side, rather than a bare click-to-withdraw-a-stack choice.
 *
 * Reads and edits in the resource's own **authored** unit - items, or millibuckets for a fluid -
 * and hands [onConfirm] the platform amount, so a caller downstream never has to know which kind it
 * was. The modal dismisses itself either way.
 */
fun LayerStackManager.requestQuantityDialog(stack: ResourceStack<ResourceComponent>, onConfirm: (Long) -> Unit) {
	modal {
		RequestQuantityDialogContent(
			stack = stack,
			onConfirm = { amount -> onConfirm(amount); dismiss() },
			onCancel = { dismiss() },
		)
	}
}

@Composable
private fun RequestQuantityDialogContent(stack: ResourceStack<ResourceComponent>, onConfirm: (Long) -> Unit, onCancel: () -> Unit) {
	// Everything here is in the unit a *player* authors and reads - items, and millibuckets rather
	// than the droplets Fabric counts fluids in. Asking for "162000" of water in a field that steps
	// by one droplet is not a thing anyone can use; the conversion back to whatever the platform
	// counts in happens once, at [onConfirm].
	val kind = ResourceKindRegistry.forResource(stack.resource)
	val max = (kind?.toAuthored(stack.amount) ?: stack.amount).coerceAtLeast(1)
	// One "unit" of whatever this is - a stack for an item, a bucket for a fluid - so the dialog
	// opens on a sensible default for any kind rather than on an item-only stack size.
	val defaultAmount = kind?.defaultAuthored ?: 1L
	// The kind's own scroll ladder: 1/4/16/64 for items, 1/10/100/1000mB for fluids, so the buttons
	// move by amounts that mean something for the kind in hand and by the same ones its cells scroll
	// by elsewhere.
	val steps = stepperStepsFor(kind)
	var amount by remember(stack) { mutableStateOf(min(max, defaultAmount)) }

	Panel(modifier = Modifier.sizeIn(minWidth = 150), contentAlignment = Alignment.Center) {
		Column(verticalArrangement = Arrangement.spacedBy(4), horizontalAlignment = Alignment.CenterHorizontally) {
			Row(horizontalArrangement = Arrangement.spacedBy(4), verticalAlignment = Alignment.CenterVertically) {
				TerminalSlot(stack)
				Text(stack.resource.displayName(), dropShadow = false, color = LocalTheme.current.darkTextColor)
			}
			Text(Component.literal("Quantity (max $max):"), dropShadow = false, color = LocalTheme.current.darkTextColor)
			QuantityStepper(amount = amount, range = 1..max, onAmountChange = { amount = it }, steps = steps)
			Row(
				horizontalArrangement = Arrangement.spacedBy(4),
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier.padding(top = 4),
			) {
				Button(onClick = { onCancel() }) { Text(Component.literal("Cancel"), dropShadow = false) }
				Button(
					// Back into whatever the platform counts in, once, here - everything above this
					// point is the authored unit.
					onClick = { onConfirm(kind?.toPlatform(amount) ?: amount) },
				) { Text(Component.literal("Request"), dropShadow = false) }
			}
		}
	}
}
