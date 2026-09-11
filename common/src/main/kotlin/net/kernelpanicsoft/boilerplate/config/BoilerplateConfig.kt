package net.kernelpanicsoft.boilerplate.config

import net.kernelpanicsoft.archie.config.CategorySpec
import net.kernelpanicsoft.archie.config.ConfigContainer
import net.kernelpanicsoft.archie.config.ConfigSpec
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.pipe.gui.StoreSortDirection
import net.kernelpanicsoft.boilerplate.pipe.gui.StoreSortMode
import net.kernelpanicsoft.boilerplate.pipe.gui.StoreViewMode
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
			/** One stack's worth by default - the quantity every item extraction has always pulled. */
			var itemExtractionBatch by long(
				Component.literal("Item extraction batch"),
				Component.literal("How many items one extraction moves at a time."),
				default = 64L,
			)

			/**
			 * How close along a segment two deliveries must be before they merge into one - see
			 * [net.kernelpanicsoft.boilerplate.pipe.entity.coalesceTravelingItems].
			 *
			 * A fraction of one segment, so it is the same distance whatever `ticksPerSegment` is.
			 * A quarter of a segment is roughly the radius vanilla merges dropped item entities
			 * over. `0` turns merging off entirely.
			 */
			var mergeProgressWindow by double(
				Component.literal("Merge window"),
				Component.literal("How close together, as a fraction of one pipe segment, two deliveries must be to merge into one. 0 disables merging."),
				default = 0.25,
			)

			/**
			 * The largest a merged delivery may get, counted in whole units of its own kind - so a
			 * stack for an item and a bucket for a fluid or chemical, whatever those are worth in
			 * the platform's own count.
			 *
			 * One extraction already moves exactly one whole, so a limit of `1` leaves nothing to
			 * merge; the default is what caps how much a saturated run can collapse by.
			 */
			/**
			 * How much may be on its way to one destination beyond what that destination can hold
			 * right now, in whole units of the resource's own kind - a stack for an item, a bucket
			 * for a fluid.
			 *
			 * Counting what is already in flight ([net.kernelpanicsoft.boilerplate.pipe.network.InboundCensus])
			 * stops an extractor filling a run with deliveries that have nowhere to land. Clamping it
			 * to *exactly* the room left, though, means a machine that is consuming has to empty a
			 * slot, be probed, and then wait out the whole trip before anything arrives - so it
			 * starves for the length of the pipe, every cycle. This is the slack that keeps the line
			 * primed: enough queued at the door to cover the trip, and a hard ceiling on how much can
			 * pile up there.
			 *
			 * The default covers a long run to a hungry machine: enough queued that the trip itself
			 * is never what the destination is waiting on. `0` sends only what fits at the moment of
			 * the pull, which is exact but leaves a consuming destination idle for one round trip out
			 * of every cycle.
			 *
			 * What a *fresh* hook starts at, not a figure every hook obeys: the right slack belongs to
			 * the run, so each extractor keeps its own
			 * ([net.kernelpanicsoft.boilerplate.pipe.hook.ExtractionHookState.queueWholes]) from the
			 * moment it is placed, and changing this afterwards seeds new hooks without disturbing
			 * anything already configured.
			 */
			var destinationQueueWholes by intSlider(
				Component.literal("Destination queue"),
				Component.literal("How much may be queued in the pipes for one destination beyond what it can currently hold, in stacks for items and buckets for fluids. Higher keeps a consuming machine fed across a long run; 0 sends only what fits right now."),
				min = 0, max = 16, default = 8,
			)

			var mergeMaxWholes by intSlider(
				Component.literal("Merge limit"),
				Component.literal("The most one merged delivery may carry, in stacks for items and buckets for fluids. 1 disables merging."),
				min = 1, max = 64, default = 8,
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

			/**
			 * How much an [net.kernelpanicsoft.boilerplate.pipe.hook.UNBOUNDED_STOCK] entry asks for
			 * per cycle.
			 *
			 * An unbounded target has no number to work toward, so it needs *some* ceiling per
			 * request or the ask is meaningless. A stack's worth per cycle keeps an export bus
			 * moving briskly without any single cycle trying to drain a whole network at once.
			 */
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

			/**
			 * How many runs' worth of a single pattern
			 * [net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookState.patternBuffers]
			 * holds before refusing more.
			 *
			 * TODO M5: derive from the target's own pressure capacity - a flat baseline until then,
			 * now tunable rather than fixed, which is not the same thing as derived.
			 */
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

			/**
			 * Big enough for a withdrawal to land in one go, and stated loader-independently for the
			 * reason [net.kernelpanicsoft.boilerplate.warehouse.tank.FluidTankBlockEntity.getCapacity]
			 * documents.
			 */
			var terminalInboxMillibuckets by long(
				Component.literal("Terminal inbox"),
				Component.literal("Millibuckets one column of a terminal's inbox holds - big enough for a withdrawal to land in one go."),
				default = 16_000L,
			)

			/** Sixteen buckets by default - the same as the Crafting Tank the buffer replaced. */
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

			/**
			 * The three shared-pool racks, each stated in the *whole* their own pool is denominated
			 * in - see [net.kernelpanicsoft.boilerplate.warehouse.rack.PooledResourceStorage], where
			 * one whole is a bucket of a fluid or a full stack of an item, deliberately equated.
			 *
			 * 64 apiece: a double chest of stacks, or a large tank's worth of fluid, which is the
			 * size at which one of these is worth building over the ordinary rack it replaces without
			 * being worth building *instead* of a warehouse.
			 */
			var distributedMultiTankBuckets by long(
				Component.literal("Distributed Multi Tank"),
				Component.literal("Buckets a Distributed Multi Tank holds in total, shared across every fluid or chemical in it however many there are."),
				default = 64L,
			)

			var distributedMultiBufferStacks by long(
				Component.literal("Distributed Multi Buffer"),
				Component.literal("Stacks a Distributed Multi Buffer holds in total, shared across every item in it - each item costing its share of its own stack size."),
				default = 64L,
			)

			var omnibufferWholes by long(
				Component.literal("Omnibuffer"),
				Component.literal("Wholes an Omnibuffer holds in total, a stack and a bucket counting as one each, shared across every resource in it."),
				default = 64L,
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

			/**
			 * Which rows a terminal lists - the sidebar's own cycle button writes straight back here
			 * (see [net.kernelpanicsoft.boilerplate.pipe.gui.TerminalPreferences]), so the selection
			 * outlives the screen rather than resetting every time one is opened.
			 */
			var terminalViewMode by enumSelector(
				Component.literal("Terminal view"),
				Component.literal("Which rows a terminal lists: what is in stock, what is craftable, or both."),
				StoreViewMode::class,
				default = StoreViewMode.BOTH,
			)

			/** Which key a terminal orders its rows by - written by the sidebar, as [terminalViewMode] is. */
			var terminalSortMode by enumSelector(
				Component.literal("Terminal sort"),
				Component.literal("What a terminal orders its rows by: name, amount, or the mod each came from."),
				StoreSortMode::class,
				default = StoreSortMode.NAME,
			)

			/** Which way [terminalSortMode] runs - written by the sidebar, as [terminalViewMode] is. */
			var terminalSortDirection by enumSelector(
				Component.literal("Terminal sort direction"),
				Component.literal("Which way a terminal's ordering runs."),
				StoreSortDirection::class,
				default = StoreSortDirection.ASCENDING,
			)
		}
	}
}
