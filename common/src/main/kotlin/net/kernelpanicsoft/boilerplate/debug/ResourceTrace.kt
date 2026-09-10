package net.kernelpanicsoft.boilerplate.debug

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.crafting.CraftingBufferJob
import net.kernelpanicsoft.boilerplate.resource.displayName
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.network.DebugTracePacket
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer

/**
 * Traces every point at which a resource changes hands, so a quantity that goes missing can be
 * followed to the exact step that lost it.
 *
 * Written for the crafting flow, which is where this hurts most: the same items pass between six
 * subsystems - a job's stock claim, a pattern provider's input buffer, a machine or vanilla table,
 * the provider's output buffer, the CPU's own pool, and the pipe network in between - and a
 * shortfall at any one of them looks identical from the outside, in that the craft simply never
 * finishes. This makes each hand-off say what it took and what it actually got.
 *
 * Not crafting-only, though, which is why it does not live in that package: the two places a
 * resource genuinely ceases to exist - a jammed delivery dropped on the ground, and a jammed fluid
 * voided outright - are pipe-network behaviour that every subsystem shares.
 *
 * **[lost] is the important one.** Every site that can silently drop a quantity reports through it
 * at WARN, whether or not tracing is on, because an item that ceases to exist is a bug rather than
 * a diagnostic - see its own KDoc.
 *
 * ## Turning it on
 *
 * Either run `/bp debug trace`, which is the same switchboard every other debug subsystem in this
 * mod is on ([DebugFlag]) and needs no restart, or launch with
 * `-Dboilerplate.crafting.trace=true` for a run that traces from the first tick - useful on a
 * dedicated server, where nobody is there to run a command.
 *
 * ## Where it goes
 *
 * To the **log of every client that asked for it**, not to the server's. The work being traced is
 * server-side, but the trace is a diagnostic a player turned on, and most of what it says is
 * repeated every tick - so writing it into a shared server's log would bury that log for an admin
 * who never asked (see [net.kernelpanicsoft.boilerplate.network.DebugTracePacket]). The two
 * exceptions both go to the server's own log as well: a run launched with the system property,
 * which has nobody to send to, and [lost], which is a bug report rather than a diagnostic.
 *
 * ## Reading the output
 *
 * Every line is `[craft] <site> ...`, so `grep '\[trace\]'` gets the whole flow and
 * `grep '\[trace\] pattern\.'` gets one stage. Amounts are always written as `want=N got=N`
 * so a stage that lost something is the first line where the two differ.
 */
object ResourceTrace {
	private const val PREFIX = "[trace]"

	/** Always-on override for a run that has to trace from the first tick - see this object's KDoc. */
	private val forced: Boolean = System.getProperty("boilerplate.crafting.trace").toBoolean()

	/** Whether the per-hand-off tracing below does anything. [lost] deliberately ignores this. */
	val enabled: Boolean get() = forced || DebugOverlayViewers.enabled(DebugFlag.TRACE)

	/** One hand-off, at [pos], that moved everything it meant to. */
	fun at(pos: BlockPos, site: String, vararg details: Pair<String, Any?>) {
		if (!enabled) return
		emit(false, "$PREFIX $site ${pos.short()} ${details.render()}")
	}

	/**
	 * One hand-off that moved [got] of [resource] when it wanted [want].
	 *
	 * Logged at INFO when it moved everything and WARN when it did not, so the first WARN in a
	 * trace is the stage that stalled - the whole point of the exercise.
	 */
	fun moved(pos: BlockPos, site: String, resource: ResourceComponent, want: Long, got: Long, vararg details: Pair<String, Any?>) {
		if (!enabled) return
		emit(got < want, "$PREFIX $site ${pos.short()} ${resource.name()} want=$want got=$got ${details.render()}")
	}

	/**
	 * [amount] of [resource] that ceased to exist at [pos].
	 *
	 * Deliberately **not** gated on [enabled], and deliberately WARN: everywhere else here reports
	 * a resource that failed to move, which is a stall and recoverable. This reports one that moved
	 * nowhere and is gone - a conservation failure that is always a bug, and that a player would
	 * otherwise only ever notice as a slowly emptying storage system.
	 */
	fun lost(pos: BlockPos, site: String, resource: ResourceComponent, amount: Long, why: String) {
		if (amount <= 0L) return
		emit(true, "$PREFIX LOST $site ${pos.short()} ${amount}x ${resource.name()} - $why", toServerLog = true)
	}

	/**
	 * One resource as the resolver decided it: how much the plan needs of it, how much it believed
	 * was already in stock, and what it intends to do about the difference.
	 *
	 * The half of the pipeline that was previously invisible. Every stall downstream is a
	 * consequence of a decision made here, and the decision that matters most is the quiet one -
	 * `shortfall=0`, meaning the resolver counted enough stock and planned no step, so nothing will
	 * ever produce it and everything waiting on it waits forever if that stock turns out not to be
	 * movable.
	 */
	fun resolved(
		from: BlockPos,
		resource: ResourceComponent,
		demand: Long,
		fromStock: Long,
		shortfall: Long,
		plan: String,
	) {
		if (!enabled) return
		emit(false, "$PREFIX resolve.demand ${from.short()} ${resource.name()} demand=$demand stock=$fromStock short=$shortfall -> $plan")
	}

	/**
	 * Where the resolver's stock figure for [resource] came from, split by source.
	 *
	 * [unclaimable] is the number worth staring at: stock counted here that
	 * [net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment.fulfillFromProvider] would
	 * refuse to move, because counting stock is deliberately ungated while *pulling* it requires a
	 * powered hook. Anything the resolver plans against that figure is a job that can never be fed.
	 */
	fun stock(from: BlockPos, resource: ResourceComponent, warehouse: Long, providers: Long, unclaimable: Long) {
		if (!enabled) return
		val line = "$PREFIX resolve.stock ${from.short()} ${resource.name()} warehouse=$warehouse providers=$providers unclaimable=$unclaimable"
		emit(unclaimable > 0, line)
	}

	/** The plan a resolve settled on, or why it could not settle on one. */
	fun plan(from: BlockPos, target: ResourceComponent, amount: Long, outcome: String) {
		if (!enabled) return
		emit(false, "$PREFIX resolve.plan ${from.short()} ${amount}x ${target.name()} -> $outcome")
	}

	/** A job's own lifecycle, which is the spine every other line hangs off. */
	fun job(pos: BlockPos, event: String, job: CraftingBufferJob, vararg details: Pair<String, Any?>) =
		job(event, job, pos.short(), *details)

	/**
	 * [job] with no position - the queue point, which runs on a cluster member that does not know
	 * where it is. Every later line for the same `id` carries one.
	 */
	fun job(event: String, job: CraftingBufferJob, vararg details: Pair<String, Any?>) =
		job(event, job, "(cluster)", *details)

	private fun job(event: String, job: CraftingBufferJob, where: String, vararg details: Pair<String, Any?>) {
		if (!enabled) return
		emit(false, "$PREFIX job.$event $where id=${job.id} target=${job.targetAmount}x${job.target.name()} done=${job.done} ${details.render()}")
	}

	/**
	 * Writes [line], collapsing an unbroken run of identical ones into a single trailing count.
	 *
	 * Most of what this traces is retried **every tick** - a drain with nowhere to go, a delivery
	 * stalled against a full machine - so without this the one line that explains a disappearance is
	 * buried under ten thousand copies of the line that merely says nothing has changed yet. A run
	 * is only ever summarised once it ends, so the count is exact and nothing is silently dropped.
	 *
	 * Server-tick-thread only, like everything that calls it.
	 *
	 * @param toServerLog writes to *this* process's log as well as to the viewers - for a line that
	 *   is a bug report rather than a diagnostic, and so has to reach the person running the server
	 *   whether or not anybody asked to see it. See [lost], the only caller that sets it.
	 */
	private fun emit(problem: Boolean, line: String, toServerLog: Boolean = false) {
		if (line == lastLine) {
			repeats++
			return
		}
		flushRepeats()
		lastLine = line
		route(problem, line, toServerLog)
	}

	/** Reports how many times the previous line repeated, if it did. Public so a caller can force it before reading a log mid-run. */
	fun flushRepeats() {
		if (repeats <= 0) return
		route(false, "$PREFIX ... previous line repeated $repeats more times", toServerLog = false)
		repeats = 0
	}

	/**
	 * Sends [line] where it belongs: to the log of every client that asked for the trace, and to
	 * this process's own log only when nobody could have.
	 *
	 * The trace describes server-side work, but it is a diagnostic somebody asked for rather than
	 * anything an operator needs, and most of what it says is repeated every tick - so writing it
	 * into a shared server's log would bury that log for an admin who never asked. [forced] is the
	 * exception, and the reason it exists: a run launched with the system property has nobody to
	 * send to, which is exactly the dedicated-server case the property was added for.
	 *
	 * A line that goes both ways appears twice in singleplayer, where the integrated server writes
	 * into the same log the client does. Left that way on purpose: de-duplicating it means deciding
	 * that the operator does not need to see a conservation failure because somebody else already
	 * has, which stops being true the moment a second player is on the same world over LAN.
	 */
	private fun route(problem: Boolean, line: String, toServerLog: Boolean) {
		if (forced || toServerLog) {
			if (problem) Boilerplate.LOGGER.warn(line) else Boilerplate.LOGGER.info(line)
		}
		if (!DebugOverlayViewers.enabled(DebugFlag.TRACE)) return
		if (pending.size >= PENDING_CAP) {
			dropped++
			return
		}
		pending += DebugTracePacket.Line(problem, line)
	}

	/**
	 * Ships this tick's lines to the clients that asked for them, from the server tick - see
	 * [DebugTracePacket] for why they are batched rather than sent one by one.
	 *
	 * Clears the buffer whatever happens, including when the last viewer left between the line
	 * being recorded and this running: a trace nobody is reading is not one to keep for later.
	 */
	fun flushToViewers(server: MinecraftServer) {
		if (pending.isEmpty() && dropped == 0) return
		val viewers = DebugOverlayViewers.viewersOf(DebugFlag.TRACE).mapNotNull { server.playerList.getPlayer(it) }
		if (viewers.isNotEmpty()) {
			BoilerplateNetworkChannel.toPlayers(viewers, DebugTracePacket(pending.toList(), dropped))
		}
		pending.clear()
		dropped = 0
	}

	private var lastLine: String? = null
	private var repeats: Int = 0

	/**
	 * This tick's lines, awaiting [flushToViewers].
	 *
	 * Server-tick-thread only, like every site that writes to it. Bounded because one tick of a
	 * badly stalled network can produce a great many lines and an unbounded buffer would turn a
	 * diagnostic into a memory problem; [dropped] says how many were lost rather than leaving a
	 * silent hole.
	 */
	private val pending = ArrayList<DebugTracePacket.Line>()
	private var dropped = 0

	private const val PENDING_CAP = 1024

	private fun ResourceComponent.name(): String = displayName().string

	private fun BlockPos.short(): String = "($x,$y,$z)"

	/** A resource renders by its own display name here too - its `toString` is an object identity, which is unreadable and changes every run. */
	private fun Array<out Pair<String, Any?>>.render(): String =
		joinToString(" ") { (key, value) ->
			"$key=" + if (value is ResourceComponent) value.name() else value
		}
}
