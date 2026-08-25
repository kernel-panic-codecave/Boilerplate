package net.kernelpanicsoft.tubularstorage.item

import net.minecraft.tags.BlockTags
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Items
import net.minecraft.world.item.Tier
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.level.block.Block
import java.util.function.Supplier

sealed class WrenchTier(
	private val incorrectBlocksForDrops: TagKey<Block?>,
	private val uses: Int,
	private val speed: Float,
	private val attackDamageBonus: Float,
	private val enchantmentValue: Int,
	private val repairIngredient: Supplier<Ingredient>
) : Tier
{
	override fun getUses(): Int
	{
		return this.uses
	}

	override fun getSpeed(): Float
	{
		return this.speed
	}

	override fun getAttackDamageBonus(): Float
	{
		return this.attackDamageBonus
	}

	override fun getIncorrectBlocksForDrops(): TagKey<Block?>
	{
		return incorrectBlocksForDrops
	}

	override fun getEnchantmentValue(): Int
	{
		return this.enchantmentValue
	}

	override fun getRepairIngredient(): Ingredient
	{
		return this.repairIngredient.get()
	}

	override fun toString(): String
	{
		return "WrenchTier[" +
				"incorrectBlocksForDrops=" + incorrectBlocksForDrops + ", " +
				"uses=" + uses + ", " +
				"speed=" + speed + ", " +
				"attackDamageBonus=" + attackDamageBonus + ", " +
				"enchantmentValue=" + enchantmentValue + ", " +
				"repairIngredient=" + repairIngredient + ']'
	}

	object Brass : WrenchTier(BlockTags.INCORRECT_FOR_IRON_TOOL, 1000, 10.0f, 0.0f, 0, { Ingredient.of(Items.IRON_INGOT) })
	object Diamond : WrenchTier(BlockTags.INCORRECT_FOR_DIAMOND_TOOL, 1561, 8.0f, 3.0f, 10, { Ingredient.of(Items.DIAMOND) })
}