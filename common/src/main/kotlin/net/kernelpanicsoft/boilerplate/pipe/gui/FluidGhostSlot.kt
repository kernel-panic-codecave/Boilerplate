package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.Composable
import earth.terrarium.common_storage_lib.context.impl.SimpleItemContext
import earth.terrarium.common_storage_lib.fluid.FluidApi
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gui.composables.input.Clickable
import net.kernelpanicsoft.archie.gui.composables.theme.TextureStates
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.Layout
import net.kernelpanicsoft.archie.gui.layout.MeasureResult
import net.kernelpanicsoft.archie.gui.layout.Renderer
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.size
import net.kernelpanicsoft.archie.gui.nodes.UINode
import net.kernelpanicsoft.archie.gui.render.AFluidRenderPlatform
import net.kernelpanicsoft.archie.gui.theme.LocalTheme
import net.kernelpanicsoft.archie.gui.util.extension.drawThemeState
import net.kernelpanicsoft.archie.gui.util.extension.invoke
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.item.ItemStack

/** The inset, in pixels, of a slot's fluid fill inside its 18x18 frame - one pixel of border all round, matching the slot texture. */
private const val FLUID_INSET = 1

/**
 * One cell of a fluid ghost grid - the fluid counterpart of [GhostSlot].
 *
 * Configured by clicking with a **fluid-containing item** on the cursor (a bucket, another mod's
 * tank): the slot reads whatever fluid that item holds and stores *the fluid*, not the container.
 * That is the only sensible gesture available - a fluid cannot be carried on the cursor the way an
 * item can, so there is nothing else to click *with* - and it is the idiom every other fluid filter
 * in the ecosystem uses. Clicking empty-handed on a filled slot clears it, exactly like [GhostSlot].
 *
 * Ghost in the same sense as [GhostSlot]: the carried container is never consumed or modified, only
 * inspected.
 */
@Composable
fun FluidGhostSlot(
	resource: FluidResource,
	carried: () -> ItemStack,
	onPlace: (FluidResource) -> Unit,
	onClear: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Clickable(
		onClick = {
			val held = fluidIn(carried())
			if (held != null) onPlace(held)
			else if (!resource.isBlank) onClear()
		},
		modifier = modifier,
	) { isHovered, _, _ ->
		FluidSlotFace(resource, isHovered)
	}
}

/** A [columns]-wide grid of [FluidGhostSlot]s over [resources], row-major - the fluid counterpart of [GhostSlotGrid]. */
@Composable
fun FluidGhostSlotGrid(
	resources: List<FluidResource>,
	columns: Int,
	carried: () -> ItemStack,
	onPlace: (Int, FluidResource) -> Unit,
	onClear: (Int) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(0)) {
		resources.chunked(columns).forEachIndexed { rowIndex, row ->
			Row(horizontalArrangement = Arrangement.spacedBy(0)) {
				row.forEachIndexed { colIndex, resource ->
					val index = rowIndex * columns + colIndex
					FluidGhostSlot(
						resource = resource,
						carried = carried,
						onPlace = { onPlace(index, it) },
						onClear = { onClear(index) },
					)
				}
			}
		}
	}
}

/**
 * The slot frame with [resource]'s own still texture and tint painted inside it, through
 * [AFluidRenderPlatform] - the same bridge [net.kernelpanicsoft.archie.gui.composables.basic.FluidTank]
 * uses, so a modded fluid looks the same here as it does anywhere else.
 *
 * The sprite is stretched to the slot interior rather than tiled: at 16x16 inside an 18x18 frame
 * that is very close to one-to-one anyway, and tiling would need manual quad emission for no
 * visible gain.
 */
@Composable
private fun FluidSlotFace(resource: FluidResource, isHovered: Boolean) {
	val theme = LocalTheme.current
	val slotState = theme.getComposableTheme("slot").getState(TextureStates.DEFAULT, theme.mode)

	Layout(
		name = "FluidGhostSlot",
		measurePolicy = { _, _, constraints -> MeasureResult(constraints.minWidth, constraints.minHeight) {} },
		modifier = Modifier.size(18, 18),
		renderer = object : Renderer {
			override fun render(
				node: UINode, x: Int, y: Int,
				guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float,
			) = guiGraphics {
				drawThemeState(slotState, x, y, node.width, node.height)
				if (resource.isBlank) return@guiGraphics

				val sprite = AFluidRenderPlatform.getStillSprite(resource.type) ?: return@guiGraphics
				val tint = AFluidRenderPlatform.getTintColor(resource.type)
				val alpha = ((tint ushr 24) and 0xFF) / 255f
				val red = ((tint ushr 16) and 0xFF) / 255f
				val green = ((tint ushr 8) and 0xFF) / 255f
				val blue = (tint and 0xFF) / 255f

				blit(
					x + FLUID_INSET, y + FLUID_INSET,
					node.width - FLUID_INSET * 2, node.height - FLUID_INSET * 2,
					0, sprite, red, green, blue, alpha,
				)
			}

			override fun renderAfterChildren(
				node: UINode, x: Int, y: Int,
				guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float,
			) {
				if (isHovered) AbstractContainerScreen.renderSlotHighlight(guiGraphics, x + 1, y + 1, 0)
			}
		},
	)
}

/**
 * The fluid [stack] contains, or `null` if it holds none.
 *
 * Goes through [FluidApi.ITEM] rather than special-casing [net.minecraft.world.item.BucketItem], so
 * any mod's fluid container works. The lookup needs an
 * [earth.terrarium.common_storage_lib.context.ItemContext] because its normal job is *moving* fluid
 * in and out of the container - here it is only ever read, so a throwaway one-slot holder around a
 * copy of the stack is enough, and the real carried stack is never touched.
 */
private fun fluidIn(stack: ItemStack): FluidResource? {
	if (stack.isEmpty) return null
	val holder = ArchieItemStorage(1)
	holder.insert(ItemResource.of(stack), stack.count.toLong(), false)
	val storage = FluidApi.ITEM.find(stack.copy(), SimpleItemContext.of(holder, 0)) ?: return null
	for (index in 0 until storage.size()) {
		val resource = storage.getResource(index)
		if (!resource.isBlank) return resource
	}
	return null
}
