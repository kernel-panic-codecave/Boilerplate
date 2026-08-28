package net.kernelpanicsoft.boilerplate.item

import net.kernelpanicsoft.boilerplate.registry.TagsRegistry
import net.minecraft.world.item.DiggerItem

/**
 * The mod's harvesting tool: every player-facing Boilerplate block declares
 * [net.minecraft.world.level.block.BlockBehaviour.Properties.requiresCorrectToolForDrops] and is
 * tagged `boilerplate:mineable/wrench`, which this item's own
 * [net.minecraft.world.component.Tool] component rule answers - so breaking with anything else
 * mines at hand speed and drops nothing, while a wrench both mines quickly and yields the block.
 *
 * The wrench itself has no durability ([net.minecraft.world.item.component.Tool.damagePerBlock]
 * is zero) - it's an infrastructure tool expected to sit in a hotbar forever, and losing pipe
 * network edits to a snapped wrench would only teach players to carry three of them.
 */
class WrenchItem(tier: WrenchTier, properties: Properties) : DiggerItem(tier, TagsRegistry.Blocks.MINEABLE_WRENCH, properties)
