package net.kernelpanicsoft.boilerplate.config

import net.kernelpanicsoft.archie.config.CategorySpec
import net.kernelpanicsoft.archie.config.ConfigContainer
import net.kernelpanicsoft.archie.config.ConfigSpec
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.minecraft.network.chat.Component

/**
 * Every number in this mod a player or pack author has a legitimate reason to change.
 *
 * - [Gameplay] is [ConfigSpec.Server]: stored per-world, and pushed to every client as it joins.
 *   These are the server's numbers wherever one exists, which is what anything governing what a
 *   network actually does has to be. The client reads them too - a pipe's contents are interpolated
 *   client-side from the same segment speed the server steps, and a synced hook state builds its own
 *   storage to render, at the same capacity - and that is exactly what the sync is for: the push
 *   lands on join, ahead of any chunk or block entity the client could read one from.
 * - [Visuals] is [ConfigSpec.Client] - read only by rendering, and none of the server's business.
 *
 * Deliberately *not* here: anything a slot index, a GUI layout or a wire format depends on. The
 * nine-slot rows (`SLOT_COUNT`, `GRID_SIZE`, `SLOTS`) are the obvious temptation and the clearest
 * example - a menu's slot indices, its screen's layout and its packets all agree on nine, and a
 * config that moved one of them would desync a client from its own server. Sentinels
 * (`UNBOUNDED_STOCK`, `NOT_BATCHED`), unit definitions (millibuckets per bucket), geometric
 * epsilons and debug-only limits are constants for the same reason: they are not preferences.
 */
object BoilerplateConfig : ConfigContainer(Boilerplate.MOD) {

	/**
	 * Throughput, timing and capacity - the numbers that decide how fast a network runs and how
	 * much it holds.
	 *
	 * Synchronized, so these are the *server's* values wherever one exists.
	 */
	object Gameplay : ConfigSpec.Server(Boilerplate.MOD, Component.literal("Gameplay")) {

		object Pipes : CategorySpec(Component.literal("Pipes")) {
			/** Ticks for a resource to cross one pipe segment at 1.0x - what [net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity]'s own per-tick progress is derived from. */
			var ticksPerSegment by intSlider(
				Component.literal("Ticks per segment"),
				Component.literal("How long a resource takes to cross one pipe segment with no pressure behind it. Lower is faster."),
				min = 1, max = 200, default = 20,
			)

			/** Available pressure at or above which a segment runs at [maxSpeedMultiplier]. In the same units as a compressor's own 4,000 capacity, so one well-fed compressor saturates a run. */
			var pressureForMaxSpeed by long(
				Component.literal("Pressure for max speed"),
				Component.literal("Pressure available to a segment at which it reaches its maximum speed. A compressor holds 4,000."),
				default = 2_000L,
			)

			/** The most pressure will scale a segment's speed by, however much is available. */
			var maxSpeedMultiplier by double(
				Component.literal("Max speed multiplier"),
				Component.literal("The fastest a fully-pressurised pipe segment runs, as a multiple of its unpressurised speed."),
				default = 3.0,
			)

			/** How many items one extraction pulls at a time - a "stack", for the item kind. */
			var itemExtractionBatch by long(
				Component.literal("Item extraction batch"),
				Component.literal("How many items one extraction moves at a time."),
				default = 64L,
			)
		}

		object Hooks : CategorySpec(Component.literal("Hooks")) {
			var extractionIntervalTicks by intSlider(
				Component.literal("Extraction interval"),
				Component.literal("Ticks between an extraction hook's pulls."),
				min = 1, max = 200, default = 10,
			)

			var requestIntervalTicks by intSlider(
				Component.literal("Requester interval"),
				Component.literal("Ticks between a requester hook's restock attempts."),
				min = 1, max = 400, default = 40,
			)

			var exportBatch by long(
				Component.literal("Requester export batch"),
				Component.literal("How much a requester hook moves in one go."),
				default = 64L,
			)

			var manageIntervalTicks by intSlider(
				Component.literal("Interface manage interval"),
				Component.literal("Ticks between an interface hook's self-restock and excess-drain passes."),
				min = 1, max = 400, default = 20,
			)

			/** How many runs' worth of a single pattern a provider buffers before refusing more. */
			var patternBufferedRuns by intSlider(
				Component.literal("Pattern buffered runs"),
				Component.literal("How many runs' worth of ingredients a pattern provider stages ahead of the machine it feeds."),
				min = 1, max = 64, default = 4,
			)
		}

		/**
		 * Stated in millibuckets and converted by whichever kind claims the column, never read from
		 * a `FluidAmounts` constant - they all read `0` in Common Storage Lib 0.0.5.
		 */
		object Capacities : CategorySpec(Component.literal("Capacities")) {
			var interfaceStockMillibuckets by long(
				Component.literal("Interface stock"),
				Component.literal("Millibuckets one column of an interface hook's stock holds."),
				default = 8_000L,
			)

			var terminalInboxMillibuckets by long(
				Component.literal("Terminal inbox"),
				Component.literal("Millibuckets one column of a terminal's inbox holds - big enough for a withdrawal to land in one go."),
				default = 16_000L,
			)

			var craftingBufferMillibuckets by long(
				Component.literal("Crafting buffer slot"),
				Component.literal("Millibuckets one slot of a Crafting Buffer holds."),
				default = 16_000L,
			)

			var bulkRackCapacity by long(
				Component.literal("Bulk rack"),
				Component.literal("How much of its single resource a Bulk Rack holds."),
				default = 1_000_000L,
			)
		}

		object Crafting : CategorySpec(Component.literal("Crafting")) {
			var cpuBasePressureCost by long(
				Component.literal("CPU base pressure cost"),
				Component.literal("Pressure a Crafting CPU draws per tick while a job is running."),
				default = 10L,
			)

			var cpuMaxPressureDraw by long(
				Component.literal("CPU max pressure draw"),
				Component.literal("The most pressure a Crafting CPU will draw per tick to run faster."),
				default = 20L,
			)
		}

	}

	/** Rendering preferences - read only on the client, and none of the server's business. */
	object Visuals : ConfigSpec.Client(Boilerplate.MOD, Component.literal("Visuals")) {

		object TravelingResources : CategorySpec(Component.literal("Traveling resources"), "traveling_resources") {
			var itemScale by float(
				Component.literal("Item scale"),
				Component.literal("How large an item riding a pipe is drawn, as a fraction of a block."),
				default = 0.4f,
			)

			var dropletRadius by float(
				Component.literal("Droplet radius"),
				Component.literal("Radius of the droplet drawn for a fluid or other measured resource riding a pipe."),
				default = 0.17f,
			)

			var spinDegreesPerTick by float(
				Component.literal("Spin speed"),
				Component.literal("Degrees per tick a traveling resource turns about its upright. Zero holds it still."),
				default = 4f,
			)

			var wobbleAmplitude by float(
				Component.literal("Wobble amplitude"),
				Component.literal("How far a traveling resource rocks off vertical. Zero holds it level."),
				default = 0.14f,
			)
		}

		object Interface : CategorySpec(Component.literal("Interface")) {
			var highlightAlpha by float(
				Component.literal("Part highlight opacity"),
				Component.literal("Opacity of the outline drawn over the multipart piece under the cursor."),
				default = 0.4f,
			)

			var craftingStatusPollMillis by long(
				Component.literal("Crafting status poll"),
				Component.literal("Milliseconds between a Crafting Buffer screen's job-status refreshes."),
				default = 250L,
			)
		}
	}
}
