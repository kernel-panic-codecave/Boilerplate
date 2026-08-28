package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.Panel
import net.kernelpanicsoft.archie.gui.composables.containers.Surface
import net.kernelpanicsoft.archie.gui.composables.input.Button
import net.kernelpanicsoft.archie.gui.composables.input.textfield.BasicTextField
import net.kernelpanicsoft.archie.gui.layer.LayerStackManager
import net.kernelpanicsoft.archie.gui.layout.Alignment
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.position.padding
import net.kernelpanicsoft.archie.gui.modifiers.sizeIn
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.archie.gui.theme.LocalTheme
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import kotlin.math.min

/**
 * Pushes a modal onto [this] asking how many of [stack] to request - a digit-only quantity field,
 * live-clamped to `1..stack.count` ([ItemStack.getCount] being the aggregated total available at
 * this terminal, which can exceed a single stack's usual max size - see
 * `docs/design/m3-warehouse-storage.md`), with `-`/`+` stepper buttons either side, rather than a
 * bare click-to-withdraw-a-stack choice. [onConfirm] fires once with the chosen amount; the modal
 * dismisses itself either way.
 */
fun LayerStackManager.requestQuantityDialog(stack: ResourceStack<ItemResource>, onConfirm: (Long) -> Unit) {
	modal {
		RequestQuantityDialogContent(
			stack = stack,
			onConfirm = { amount -> onConfirm(amount); dismiss() },
			onCancel = { dismiss() },
		)
	}
}

@Composable
private fun RequestQuantityDialogContent(stack: ResourceStack<ItemResource>, onConfirm: (Long) -> Unit, onCancel: () -> Unit) {
	val max = stack.amount.coerceAtLeast(1)
	var amount by remember(stack) { mutableStateOf(min(max, stack.resource.item.defaultMaxStackSize.toLong())) }
	var text by remember(stack) { mutableStateOf(amount.toString()) }

	fun setAmount(new: Long) {
		amount = new.coerceIn(1, max)
		text = amount.toString()
	}

	Panel(modifier = Modifier.sizeIn(minWidth = 150), contentAlignment = Alignment.Center) {
		Column(verticalArrangement = Arrangement.spacedBy(4), horizontalAlignment = Alignment.CenterHorizontally) {
			Row(horizontalArrangement = Arrangement.spacedBy(4), verticalAlignment = Alignment.CenterVertically) {
				TerminalSlot(stack)
				Text(stack.resource.cachedStack.hoverName, dropShadow = false, color = LocalTheme.current.darkTextColor)
			}
			Text(Component.literal("Quantity (max $max):"), dropShadow = false, color = LocalTheme.current.darkTextColor)
			Row(horizontalArrangement = Arrangement.spacedBy(2), verticalAlignment = Alignment.CenterVertically) {
				Button(onClick = { setAmount(amount - 1) }) { Text(Component.literal("-"), dropShadow = false) }
				BasicTextField(
					value = text,
					onValueChange = { raw ->
						val digits = raw.filter { it.isDigit() }
						text = digits
						digits.toLongOrNull()?.let { setAmount(it) }
					},
					modifier = Modifier.width(60),
				)
				Button(onClick = { setAmount(amount + 1) }) { Text(Component.literal("+"), dropShadow = false) }
			}
			Row(
				horizontalArrangement = Arrangement.spacedBy(4),
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier.padding(top = 4),
			) {
				Button(onClick = { onCancel() }) { Text(Component.literal("Cancel"), dropShadow = false) }
				Button(
					enabled = text.toLongOrNull()?.let { it in 1..max } == true,
					onClick = { onConfirm(amount) },
				) { Text(Component.literal("Request"), dropShadow = false) }
			}
		}
	}
}
