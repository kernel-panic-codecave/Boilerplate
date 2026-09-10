package net.kernelpanicsoft.boilerplate.resource

/**
 * How a [ResourceKind]'s amounts are counted - the trait that decides what a "one" of it is, and so
 * what every amount-shaped default around it should be.
 *
 * Kinds differ in more than what they carry: an item's `1` is one whole indivisible thing, a fluid's
 * is a thousandth of the bucket a player actually thinks in, and a pressure reading's is a point on
 * a dial that was never a count of anything. Code that has to pick a step size, a ceiling or a
 * label has to know which of those it is holding, and asking `is this the item kind` puts every such
 * decision out of reach of any kind an addon registers. Declaring the measure instead lets a new
 * kind inherit the behaviour that already suits it.
 *
 * @see ResourceKind.measure
 */
enum class ResourceMeasure {
	/**
	 * Whole, indivisible things that are counted: items.
	 *
	 * The platform count *is* the authored one and `1` is the smallest amount there is, so the
	 * ladder can only run upward - and it runs in the sizes the kind's own quantities come in (a
	 * stack and its quarters) rather than in powers of ten, because nobody asks for ten of
	 * something the way they ask for a stack of it.
	 */
	DISCRETE,

	/**
	 * A divisible substance measured in units of a larger whole: fluids, Mekanism chemicals.
	 *
	 * The authored unit (a millibucket) is a fraction of the one a player names things in (a
	 * bucket) and is usually not what the platform counts in either - Fabric counts fluids in
	 * droplets - so [ResourceKind.toAuthored] genuinely converts. Its notch sits in the middle of
	 * its own range, which is what leaves room for a decimal ladder in both directions.
	 */
	UNIT,

	/**
	 * A level with no identity of its own: pressure, and anything else backed by a
	 * [earth.terrarium.common_storage_lib.storage.base.ValueStorage] rather than by a stack.
	 *
	 * There is only ever one of it, so an amount is a reading rather than a quantity *of* some
	 * particular thing - which is why such a kind has no meaningful slot, no per-resource filter,
	 * and nothing to put on a warehouse shelf. Measured like [UNIT] for anything that does have to
	 * put a number in front of a player.
	 */
	SCALAR,
}
