package net.kernelpanicsoft.tubularstorage.pipe.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.Panel
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

/**
 * Pushes a modal asking how much of [resource] to craft - unlike [requestQuantityDialog], not
 * clamped to current stock: [menu]'s [TerminalHookMenu.craftPreview] instead shows how much of the
 * typed amount is *actually* resolvable right now, re-requested via
 * [TerminalHookMenu.requestCraftPreview] on every amount change (a live dry run, `simulate = true`
 * in effect - see `docs/design/m4-crafting-automation.md`). [onConfirm] fires once with the chosen
 * amount; the modal dismisses itself either way.
 */
fun LayerStackManager.requestCraftQuantityDialog(menu: TerminalHookMenu, resource: ItemResource, onConfirm: (Long) -> Unit) {
	modal {
		CraftQuantityDialogContent(
			menu = menu,
			resource = resource,
			onConfirm = { amount -> onConfirm(amount); dismiss() },
			onCancel = { dismiss() },
		)
	}
}

@Composable
private fun CraftQuantityDialogContent(menu: TerminalHookMenu, resource: ItemResource, onConfirm: (Long) -> Unit, onCancel: () -> Unit) {
	var amount by remember(resource) { mutableStateOf(resource.item.defaultMaxStackSize.toLong()) }
	var text by remember(resource) { mutableStateOf(amount.toString()) }

	fun setAmount(new: Long) {
		amount = new.coerceIn(1, MAX_REQUEST)
		text = amount.toString()
	}

	LaunchedEffect(resource, amount) { menu.requestCraftPreview(resource, amount) }
	val preview = menu.craftPreview?.takeIf { it.first == resource }?.second

	Panel(modifier = Modifier.sizeIn(minWidth = 150), contentAlignment = Alignment.Center) {
		Column(verticalArrangement = Arrangement.spacedBy(4), horizontalAlignment = Alignment.CenterHorizontally) {
			Row(horizontalArrangement = Arrangement.spacedBy(4), verticalAlignment = Alignment.CenterVertically) {
				TerminalSlot(ResourceStack(resource, amount))
				Text(resource.cachedStack.hoverName, dropShadow = false, color = LocalTheme.current.darkTextColor)
			}
			Text(Component.literal("Quantity:"), dropShadow = false, color = LocalTheme.current.darkTextColor)
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
			Text(
				when (preview) {
					null -> Component.literal("Checking…")
					else -> Component.literal("Craftable now: $preview / $amount")
				},
				dropShadow = false,
				color = LocalTheme.current.darkTextColor,
			)
			Row(
				horizontalArrangement = Arrangement.spacedBy(4),
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier.padding(top = 4),
			) {
				Button(onClick = { onCancel() }) { Text(Component.literal("Cancel"), dropShadow = false) }
				Button(
					enabled = text.toLongOrNull()?.let { it in 1..MAX_REQUEST } == true && (preview ?: 0) > 0,
					onClick = { onConfirm(amount) },
				) { Text(Component.literal("Craft"), dropShadow = false) }
			}
		}
	}
}

private const val MAX_REQUEST = 6400L
