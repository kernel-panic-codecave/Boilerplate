package net.kernelpanicsoft.boilerplate.compat.mekanism

import earth.terrarium.common_storage_lib.lookup.ItemLookup
import earth.terrarium.common_storage_lib.lookup.RegistryEventListener
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.capabilities.ItemCapability
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent
import java.util.function.Consumer

/** [MappedBlockLookup]'s item-side twin - see that class for what the two translation directions are for. */
class MappedItemLookup<T, M, C>(
	private val capability: ItemCapability<T, C>,
	private val to: (T) -> M,
	private val from: (M) -> T,
) : ItemLookup<M, C>, RegistryEventListener {

	private val registrars: MutableList<Consumer<ItemLookup.ItemRegistrar<M, C>>> = ArrayList()

	init {
		registerSelf()
	}

	override fun find(stack: ItemStack, context: C): M? = stack.getCapability(capability, context)?.let(to)

	override fun onRegister(registrar: Consumer<ItemLookup.ItemRegistrar<M, C>>) {
		registrars.add(registrar)
	}

	override fun register(event: RegisterCapabilitiesEvent) {
		registrars.forEach { consumer ->
			consumer.accept(ItemLookup.ItemRegistrar { getter, items ->
				event.registerItem(capability, { stack, context -> getter.getContainer(stack, context)?.let(from) }, *items)
			})
		}
	}
}
