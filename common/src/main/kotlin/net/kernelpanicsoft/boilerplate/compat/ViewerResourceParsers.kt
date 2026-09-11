package net.kernelpanicsoft.boilerplate.compat

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack

/**
 * The reverse of [ViewerResourceStacks]: one of a recipe viewer's own ingredients read back as a
 * resource of some [net.kernelpanicsoft.boilerplate.resource.ResourceKind].
 *
 * Needed wherever a recipe the *viewer* is showing has to become something this mod can store - a
 * pattern authored from a displayed recipe, say. The forward direction is a lookup keyed by kind,
 * because the caller already holds the resource and knows its kind; here nothing is known about the
 * ingredient in advance, so each registered parser is offered it in turn and the first one that
 * recognises it wins.
 *
 * Registration order is therefore meaningful in a way [ViewerResourceStacks]' is not: a parser that
 * claims ingredients another kind would also accept must not be registered ahead of it. In practice
 * the viewers' ingredient types are disjoint per kind (an item stack is never a fluid), so this only
 * matters to an addon deliberately wrapping another kind's representation.
 *
 * @param T the viewer's own ingredient type.
 */
class ViewerResourceParsers<T> {
	private val parsers = LinkedHashMap<String, (T) -> ResourceStack<ResourceComponent>?>()

	/**
	 * Teaches this viewer's reverse lookup how to recognise [kindTag]'s resources. Replaces any
	 * previous parser for that kind.
	 *
	 * @param parse returns the resource and the amount [T] carries, or `null` when the ingredient
	 *   belongs to some other kind entirely.
	 */
	fun register(kindTag: String, parse: (ingredient: T) -> ResourceStack<ResourceComponent>?) {
		parsers[kindTag] = parse
	}

	/**
	 * [ingredient] as a resource and an amount, or `null` when no registered kind recognises it -
	 * an ingredient type nothing has a parser for, or an empty one.
	 *
	 * The amount is in the resource kind's own **platform** unit, since that is what each viewer's
	 * matching [ViewerResourceStacks] converter was handed to build the ingredient in the first
	 * place. Round-tripping through the pair is therefore lossless by construction.
	 */
	fun of(ingredient: T): ResourceStack<ResourceComponent>? {
		for (parse in parsers.values) parse(ingredient)?.let { return it }
		return null
	}
}
