package net.kernelpanicsoft.tubularstorage.pipe.item

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item

/** Attaches a [net.kernelpanicsoft.tubularstorage.pipe.hook.PipeHookType] (by [hookId]) to a pipe face on right-click - see [net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock.useItemOn]. */
class HookItem(properties: Properties, val hookId: ResourceLocation) : Item(properties)
