package net.kernelpanicsoft.boilerplate.pipe.block

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.registry.EncasementTypeRegistry
import net.kernelpanicsoft.boilerplate.registry.HookTypeRegistry
import net.kernelpanicsoft.boilerplate.registry.LootRegistry
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.functions.LootItemFunction
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType
import net.minecraft.world.level.storage.loot.parameters.LootContextParams

/**
 * One piece of a [MultipartBlock]'s contents, emitted as a drop: which attachment (or the
 * underlying pipe itself) this function instance stands for. The loot table carries one entry per
 * [Part], each wrapping its own placeholder item stack that [apply] fully replaces - a part that
 * isn't present on the broken segment yields an empty stack, i.e. no drop.
 *
 * This lives in the loot table rather than in ad-hoc removal code so every destruction path gets
 * it for free - player breaks, explosions, pistons - since the segment's attachments exist only as
 * block-entity data no static loot entry could otherwise see. The segment itself comes from the
 * block loot context's own `BLOCK_ENTITY` parameter - captured before the block (and with it the
 * entity) is removed, so the world lookup this would otherwise need finds nothing; anything not
 * running under `minecraft:block` parameters never reaches here.
 */
class MultipartContentsLootFunction(private val part: Part) : LootItemFunction {
	override fun apply(stack: ItemStack, context: LootContext): ItemStack {
		val tile = context.getParamOrNull(LootContextParams.BLOCK_ENTITY) as? MultipartBlockEntity ?: return ItemStack.EMPTY
		val drop: ItemStack? = when (part) {
			Part.PIPE -> tile.pipeBlockId.takeIf { it != MultipartBlockEntity.NONE }
				?.let { BuiltInRegistries.BLOCK.get(it).asItem().defaultInstance }
			Part.ENCASEMENT -> tile.encasement.value?.type
				?.let { EncasementTypeRegistry.byId(it)?.asItem()?.defaultInstance }
			Part.HOOK_DOWN,
			Part.HOOK_EAST,
			Part.HOOK_NORTH,
			Part.HOOK_SOUTH,
			Part.HOOK_UP,
			Part.HOOK_WEST,
			-> tile.hooks[Direction.valueOf(part.name.removePrefix("HOOK_")).name]?.type
				?.let { HookTypeRegistry.byId(it)?.asItem()?.defaultInstance }
		}
		return drop ?: ItemStack.EMPTY
	}

	override fun getType(): LootItemFunctionType<*> = LootRegistry.MultipartContents

	/** Which single piece of the segment's block entity this drop entry reads. */
	enum class Part {
		PIPE, ENCASEMENT,
		HOOK_DOWN, HOOK_EAST, HOOK_NORTH, HOOK_SOUTH, HOOK_UP, HOOK_WEST;

		companion object {
			val CODEC: Codec<Part> = Codec.STRING.xmap({ Part.valueOf(it.uppercase()) }, { it.name.lowercase() })
		}
	}

	/** [net.minecraft.data.loot.LootTableSubProvider]-side builder; the datagen loot provider applies one per entry. */
	class Builder(private val part: Part) : LootItemFunction.Builder {
		override fun build(): LootItemFunction = MultipartContentsLootFunction(part)
	}

	companion object {
		val CODEC: MapCodec<MultipartContentsLootFunction> = RecordCodecBuilder.mapCodec { instance ->
			instance.group(
				Part.CODEC.fieldOf("part").forGetter { it.part },
			).apply(instance, ::MultipartContentsLootFunction)
		}
	}
}
