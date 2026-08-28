package net.kernelpanicsoft.boilerplate.pipe.hook

/**
 * Self-contained state for one [RequesterHookType] attachment. [request]'s single slot doubles as
 * the whole config: an empty slot means no standing order, and a filled slot's item identity plus
 * *stack count* are the requested resource and the quantity to keep stocked in the adjacent
 * inventory - no separate amount field or dedicated GUI needed, "put a stack of what you want" is
 * the whole interaction.
 */
class RequesterHookState : HookHolderState(RequesterHookType.ID) {
	val request by itemField(1)
	var ticksSinceRequest: Int = 0
}
