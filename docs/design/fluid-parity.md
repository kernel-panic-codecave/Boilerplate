# Fluid Parity

See [README.md](README.md) for shared conventions and [m1-pipe-network.md](m1-pipe-network.md) for the pipe network and BFS routing this extends. Fluids are **not** a roadmap milestone — this is net-new work layered onto M1/M2's existing generic machinery, so this doc is staged by dependency rather than numbered alongside M1–M6.

The short version: the *abstraction* for multi-resource networks is already built and good. The *fluid instance* of it is a shell — nothing can put a fluid into a pipe, so `FluidPipeRouter.findRoute` has no callers anywhere in the codebase and the whole fluid transport path is unreachable dead code.

## Current state

### Already done, and worth not re-deriving

| Layer | Where | State |
|---|---|---|
| Wire/NBT serialization | `network/ResourceSerializers.kt`, `ResourceKind`, `ResourceKindRegistry` | Fully registry-driven and kind-dispatched. Fluid registered. An addon's gas kind needs no edit here. |
| Network type abstraction | `pipe/network/NetworkType.kt` | `NetworkType` / `ResourceNetworkType<T>` / `PipeCarriage`. `FluidNetworkType` is registered `PRIMARY`, so the plain pipe already conducts fluid. |
| Topology | `FluidPipeNetwork`, `FluidNetworkManager` | Works, via the shared `AbstractPipeNetworkManager`. |
| Transport envelope | `pipe/entity/TravelingItem.kt` | Holds `SResourceStack<*>` — already kind-agnostic. |
| Transport loop | `PipeBlockEntity.tick` | Dispatches through `networkTypeForResource` + `ResourceNetworkType.deposit`. Kind-agnostic apart from the guarded item-reservation branch. |

### Fixed in this pass (breakages only)

These were latent traps that would fire the moment a fluid actually moved. Fixing them does not make fluids work — it makes the codebase safe to build fluids on.

1. **`pipe/client/TravelingItemRenderer.kt`** — `stack.resource as ItemResource` was an unchecked cast inside the level render loop. A single fluid in a pipe would have been a `ClassCastException` taking down the whole frame, not one sprite. Now `as? ItemResource ?: continue`; non-item envelopes are skipped until Stage 3 gives them a visual.
2. **`pipe/entity/PipeBlockEntity.kt` `redirectedDelivery`** — used `ItemPipeRouter` and `as ItemResource` directly on the cancelled-reservation reroute path. Now resolves through `networkTypeForResource(...)` and a new `ResourceNetworkType.route(...)` wildcard bridge, matching the existing `deposit`/`jam` pattern.
3. **`pipe/network/PipeRouter.kt` `FluidPipeRouter.acceptsByFilter`** — returned a blanket `true`. This is genuinely reachable: a plain pipe carries the item *and* fluid networks simultaneously, so a fluid routing past an item `FilterHookType` hook lands here, and unconditionally accepting would leak fluid into destinations a player explicitly whitelisted away from, silently and unconfigurably. Now returns `routing.mode == FilterMode.BLACKLIST` — a fluid never *matches* an item-only card, so mode decides, exactly as it already does for a non-matching item or a blank card slot.

The two remaining `as ItemResource` casts in `PipeBlockEntity` (lines ~189, ~201) are inside the `networkType === ItemNetworkType` guard and are safe; `networkTypeForResource` only ever returns `ItemNetworkType` for an `ItemResource`.

### Stage 1, done

- `ResourceNetworkType.extractionBatch` and `ResourceNetworkType.extractRoutable` — the extraction half of the wildcard bridge, alongside `deposit`/`jam`/`route`.
- `ExtractionHookType` is kind-parametric: it asks each carrier its own segment conducts, in a **stable order** (sorted by registry id — `primaryNetworkTypesAt` returns a `hashSetOf`, so leaving it unsorted would make a face exposing two capabilities drain a different kind on different launches).
- `compatibleNetworkTypes` derives from the live registry instead of a literal `{item}`, and is a computed `get()` rather than `by lazy` so it cannot be captured before the registry is populated.
- `FluidPipeNetworkGameTest` **was never registered in `boilerplateGameTests()`** and had therefore never run once. Now registered, plus four new tests.
- **A fluid tank block** (`warehouse/tank/`), the mod's first fluid container - one slot, filled and drained through `FluidApi` like a rack, with a server-side contents readout on right-click. Not `@Sync`'d: Archie's block-entity sync resolves a packet serializer from the property type, and a bare `FluidResource` has none, which crashes the server tick loop rather than merely failing to sync.
- **`ResourceIdentity`** plus `ResourceKind.identityOf`, the per-kind value-identity adapter. Forced by the warehouse index being a resource-keyed map (see blocker 2).
- **The warehouse indexes fluid tanks.** `WarehouseIndex` is keyed by `ResourceIdentity` and scans item *and* fluid capabilities; `locations[x]` became `slotsFor(x)` across ~10 call sites. Defrag and the terminal explicitly filter to items, since `GantryJob.Move` and the terminal grid are item-typed.
- End-to-end proof: tank -> generified extraction hook -> pipe -> tank, asserting the full volume arrives.

### Still missing

- **No fluid GUI beyond the ghost slot.** The tank has no screen (right-click prints its contents), and the warehouse terminal indexes tanks but cannot list them.
- **Crafting is entirely item-typed** — gantry jobs, patterns, CPU. The warehouse *index* now handles fluids; moving them with the gantry does not.

## Upstream blockers found while implementing Stage 1

Both are bugs in `common_storage_lib 0.0.5` itself, not in Boilerplate, and both were invisible for as long as no fluid ever moved. Verified empirically in a gametest and against the shipped bytecode.

### 1. Every `FluidAmounts` constant reads `0` — on both loaders

`FluidAmounts.BUCKET`, `BOTTLE`, `BLOCK`, `INGOT` and `NUGGET` are all `0` at runtime. The `@Expect` class in `common-storage-lib-resources-common` is a bare stub, and the *platform* class in `-resources-fabric` carries the linked method bodies but **no `<clinit>` static initializer at all** — CSL's multiplatform linking carries `@Actual` methods across but not `@Actual` fields. `javap` on the shipped fabric class shows `toMillibuckets` correctly compiled against `81000` while the five constants have no initializer.

This is nastier than an ordinary wrong value, because a zero volume has no symptom. A zero batch size extracts nothing; and a zero-amount envelope that does get into a pipe satisfies `inserted >= item.stack.amount` on arrival (`0 >= 0`), so the transport loop treats it as *fully delivered* and drops it. The observable behaviour is "fluids quietly do nothing", with no error anywhere.

**Workaround, verified:** the *methods* are linked correctly. `FluidAmounts.toPlatformAmount(1000)` returns `81000` on Fabric (and would return `1000` on NeoForge); `toMillibuckets(81000)` returns `1000`. So volumes are named in millibuckets and converted through `toPlatformAmount`, never read from a constant. `FluidNetworkType.extractionBatch` does this, and `testEachCarrierNetworkBringsItsOwnExtractionBatch` asserts the result is non-zero specifically to catch a regression here.

### 2. `FluidResource` has no value equality

`ItemResource` overrides `equals`/`hashCode` (on type + components); `FluidResource` does not, so two `FluidResource.of(Fluids.WATER)` instances are unequal and hash differently.

The consequence worth acting on is `PipeRouter`'s route cache. `CacheKey` is a data class holding the `resource`, so for fluids **every lookup misses and every found route adds a new entry** — the cache does no caching and grows without bound as fluid extraction runs. (The item cache already only ever grows, since entries are invalidated by `network.version` changing the key rather than by eviction; fluids make that strictly worse.)

Not fixed here, because the right fix is the same per-kind identity adapter Stage 2 needs on `ResourceKind` — a stable key from `getType()` + `getDataPatch()` — rather than a fluid-shaped patch inside `PipeRouter`. **Decide before shipping fluid extraction to players.**

### 3. `ArchieFluidSlot` compares fluids by reference — **this one is ours**

`ArchieFluidSlot.insert` does `this.resource == unit`, and since `FluidResource` overrides no
`equals`, that is reference identity. A blank slot takes the first insert through its
`resource.isBlank()` branch; every later insert compares a freshly NBT-deserialised resource against
the held one, fails, and returns `0`. `extract` has the same shape.

The symptom is a tank that accepts exactly one delivery and then silently refuses everything, with
the rejected travellers stalling in the pipe forever - which is exactly how it was found (the
end-to-end test drained a 4-bucket source and delivered 1 bucket, with 3 stuck in the segment).

Worked around in Boilerplate by [`FluidTankStorage`][../../common/src/main/kotlin/net/kernelpanicsoft/boilerplate/warehouse/tank/FluidTankStorage.kt],
a small `CommonStorage<FluidResource>` that compares through `ResourceIdentity`. **The real fix
belongs in Archie** - it is Archie's own class, and every future fluid container built on
`ArchieFluidStorage` inherits the bug. Once fixed there, `FluidTankStorage` can be deleted and the
tank can go back to `fluidField`.

## Resolved design decision

**Hooks are generified, not duplicated.** `ExtractionHookType`/`InterfaceHookType` become parametric over `ResourceNetworkType` rather than gaining `FluidExtractionHookType` siblings. Rationale: it matches the codebase's stated intent throughout `NetworkType.kt` and `ResourceKindRegistry.kt` that an addon's gas kind works with no edits to Boilerplate, and it means one hook item per behavior rather than N per resource kind. Where semantics genuinely diverge (batch sizes, jam behavior) the difference lives on the `ResourceNetworkType` entry, not in the hook.

## Stage 1 — Kind-parametric hooks (the unblock)

Everything else depends on this; until it lands, no fluid can enter a network and none of the later stages are testable.

**The variance problem, and where the work goes.** A hook holding a `ResourceNetworkType<*>` cannot call `storage.insert`/`extract`: `CommonStorage<T>`'s `T` is in a contravariant position, so a star projection won't compile — the storage will not accept the very resource it just handed back. The fix is the pattern `NetworkType.kt` already uses for `deposit`/`jam`/`route`: push the whole operation *into* `ResourceNetworkType<T>`, where `T` is bound, and hand the result back through a **non-generic** type so nothing downstream has to re-open it. As built:

```kotlin
fun extractRoutable(
    level: ServerLevel, from: BlockPos, sourcePos: BlockPos,
    face: Direction, color: DyeColor?,
): RoutedExtraction?

data class RoutedExtraction(val stack: SResourceStack<*>, val route: List<BlockPos>)
```

The hook iterates the pipe's own carried types and asks each in turn, holding only wildcards. Note the route is resolved *before* the real extraction and excludes `sourcePos`, so a pull with nowhere to go leaves the source untouched rather than stranding the resource mid-pipe.

**Batch size belongs on the network type, not the hook.** `ExtractionHookType.EXTRACTION_AMOUNT = 64L` was an item quantity; `ResourceNetworkType.extractionBatch` replaces it — `64L` for items, one platform bucket for fluids.

> **Platform trap, and worse than expected:** volumes differ per loader (81000 droplets on Fabric, 1000 mB on NeoForge) so they can never be hardcoded — *and* the obvious constant to read them from, `FluidAmounts.BUCKET`, is itself broken and returns `0` on both. Name volumes in millibuckets and convert with `FluidAmounts.toPlatformAmount`. See "Upstream blockers" above.

**Also in this stage:**

- ~~`compatibleNetworkTypes` derived rather than a literal set~~ — done, as a computed `get()`.
- **Carrier iteration order must be stable.** `primaryNetworkTypesAt` returns a `hashSetOf`, whose order is arbitrary *and* free to differ between runs; a face exposing both an item and a fluid capability would otherwise drain a different kind on different launches. Sorted by registry id — stable but deliberately meaningless, not a priority.
- `InterfaceHookType`/`InterfaceHookState` hold an item `stock` buffer; a fluid interface needs a per-kind buffer. **Still to do** — the messiest single piece of Stage 1, deliberately left until extraction was proven.
- Fluid `onJam` already voids correctly (`NetworkType.kt`) — no world item exists for a fluid and placing a source block would be destructive. Nothing to do.

**Tests:** a fluid envelope now provably hops between plain pipes. The full source→sink test still needs a fluid container to exist (see "Still missing").

## Stage 2 — Fluid filter conditions — **done**

`FilterContext.resource` is now a `ResourceComponent`, so a fluid is an ordinary input to a filter
card. This is what makes a sorting hook usable on a fluid line at all: before it,
`FluidPipeRouter.acceptsByFilter` could evaluate nothing and a whitelist denied every fluid
unconditionally.

The conditions did not generalise uniformly, and the split is worth knowing:

| Condition | Status |
|---|---|
| `ColorConditionType` | Already kind-agnostic — tests `context.color` only, no change |
| `ModConditionType` | **Done** — namespace of `ResourceKind.registryId` |
| `RegexConditionType` | **Done** — matches against `ResourceKind.registryId` |
| `TagConditionType` | **Done** — searches `ResourceKind.tagsOf`, exact and `*`-wildcard alike |
| `CombinedConditionType` | Fell out free — it only delegates |
| `ItemConditionType` | Item-only **by nature**; a fluid never matches it |
| `FluidConditionType` | **New** — the fluid counterpart, naming fluids outright |

`ResourceComponent` exposes only data components — no registry key and no tags — so the two new
`ResourceKind` members are what carry it. `tagsOf` returns the whole tag set rather than an
`isIn(tag)` predicate deliberately: the condition supports `*` wildcards and has to enumerate
anyway, and an exact match is then a search of the same list, so one method serves both without the
kind needing to know which registry a `TagKey` would be built against.

**The fail-safe matters.** An item ghost grid cannot express a fluid, so it never *matches* one, and
the card's mode then decides exactly as it does for an item that failed to match: a whitelist denies
the fluid, a blacklist passes it. An item-only card must not silently wave fluids through a
whitelist a player set up to keep things out.

`FluidConditionType` is the fluid counterpart of the item ghost grid, with a real **fluid ghost
slot** (`FluidGhostSlot`/`FluidGhostSlotGrid`) rather than a text field. A fluid can't be carried on
the cursor the way an item can, so a slot is configured by clicking it with a **fluid-containing
item** - a bucket, another mod's tank - which is read through `FluidApi.ITEM` and stored as *the
fluid*, not the container. The carried stack is only ever inspected, never consumed. The same idiom
every other fluid filter in the ecosystem uses, and it works for any mod's container rather than
special-casing `BucketItem`.

Membership is compared through `ResourceIdentity`, not `==` — a card configured with one water
instance and tested against another (which is exactly what happens once the configured fluid has
been through NBT and the tested one has come off a pipe) would never match otherwise. There is a
test that fails against a naive comparison.

## Stage 3 — In-pipe fluid visuals — **done**

A fluid in transit renders as a **rippling icosahedron tumbling on all three axes**, skinned with
the fluid's own still texture (`TravelingFluidRenderer`). A fluid has no item model to borrow and a
textured cube reads as a block of ice; an icosahedron is the cheapest solid that reads as round,
and displacing each vertex along its own radius on a position-phased sine wave makes the surface
slosh rather than tumble rigidly. Geometry is generated once in unit-radius object space and reused
for every droplet and frame - the same bake-once discipline the debug overlays follow.

> **Platform trap, resolved:** sprite/tint lookup is loader-specific (`FluidRenderHandlerRegistry`
> on Fabric, `IClientFluidTypeExtensions` on NeoForge). Wired as `FluidSpriteSource`/`FluidSprites` -
> an interface injected once from each loader's client init, matching how `PressureApi` already
> handles its own platform seam.
>
> **Archie already solves this.** `AFluidRenderPlatform` (`net.kernelpanicsoft.archie.gui.render`)
> is an `expect`/`actual` object over exactly `getStillSprite`/`getTintColor`, with both loaders
> implemented, and Archie has the Actualizer plugin set up to build it. Boilerplate compiles against
> the stubbed `expect` in `archie-common` and gets the real one at runtime - the same way it already
> consumes `AGameTestPlatform`. Both the travelling-fluid renderer and the fluid ghost slot go
> through it, so Boilerplate needs no platform seam of its own for fluids at all.
>
> Setting Actualizer up *in Boilerplate* was tried before that was found, and reverted: the plugin
> makes each loader module compile all of `common`'s sources, so every module then needs `common`'s
> compile-only recipe-viewer APIs (REI/JEI/EMI), and adding the Fabric-flavoured `rei.common`
> artifact to the NeoForge module breaks Loom's Minecraft setup outright (`Cannot get
> MojangMappedMinecraftProvider before it has been setup`). Worth revisiting only if Boilerplate
> ever needs a seam Archie doesn't already provide.

## Stage 4 — GUI parity

Fluid ghost slots, tank widgets, and fluid rendering in the pipe/hook screens (~14 files under `pipe/gui/` are item-typed). Depends on Stage 3 for the sprite/tint abstraction.

## Stage 5 — Warehouse and crafting

The largest tier by far, and independent of Stages 1–4 shipping. Realistically this is "make the warehouse and crafting layers resource-kind-generic", not "add fluids to them".

### Crafting — **done**

The whole crafting layer is now generic over `ResourceComponent`:

- **`Pattern` holds any kind on either side.** `inputs`/`outputs` are `SResourceStack<SResourceComponent>`, so `1000mB water + 1 clay -> 1 slurry` is one pattern. `CRAFTING` patterns stay item-only and `PatternEncoder` *rejects* a fluid cell rather than dropping it — dropping it would match a different, smaller vanilla recipe and encode a pattern the player never authored.
- **Amounts are per cell, not per occupancy.** A pattern that wants 64 of something is one entry of 64. The old "count how many of the 9 cells hold it" reading has no meaning for a fluid.
- **Every per-resource map is keyed by `ResourceIdentity`.** `requiredInputs()`, `stockPulls`, `fedAmounts`, `outstandingStockClaims`. This is not optional: `FluidResource` still has no `equals`, so a raw-resource key silently reports "absent" for a fluid that is plainly present.
- **`CraftingResolver`, `CraftingBufferJob`, `CraftingRequest`** are generic, and job execution moved out of `CraftingBufferEncasementType` into `CraftingCpuRuntime`, shared by every member kind.
- **A Crafting Tank** (`CraftingTankEncasementType`) is the fluid-holding CPU member: same cluster, same job queue, contributing tanks instead of item slots. A cluster's leader may be either kind. See below.
- **Fluid stock claiming** works through provider/interface hooks (`RequestFulfillment.fulfillFluidFromProvider`).

Ghost-slot amounts for a fluid are authored and displayed in **millibuckets** and converted to platform units once, at encode time, in `PatternTerminalHookMenu.cellAmount` — everything downstream then compares in platform units without knowing.

### Still item-only

- **Warehouse retrieval of a fluid.** `claimAndEnqueue`/`enqueueRetrieve` are item-typed, and a fluid retrieve means a gantry job carrying it. A fluid raw material must currently be reachable through a provider or interface hook, or already sit in the CPU's own tanks. This is the one real gap in fluid autocrafting.
- **The terminal's store/craft grid.** `StoreEntry`, `combineStoreEntries` and the stock rows are item-shaped, so `AbstractTerminalHookMenu.sendCraftableList` deliberately filters to item outputs. A fluid-producing pattern runs perfectly well as a *step* inside a craft; it just isn't independently requestable from the terminal yet. That is Stage 4's job.
- **Racks, `GantryJob`, `WarehouseDefragPlanner`.**

## Suggested order

1. Stage 1 extraction path + end-to-end gametest — the smallest change that makes fluids real
2. Stage 1 interface-hook buffer
3. ~~Stage 3 in-pipe visuals~~ — done
4. ~~Stage 2 filter conditions~~ — done bar the fluid ghost card, which needs Stage 4's slot
5. Stage 4 GUI — a tank screen and a terminal listing (the warehouse indexes tanks already; the terminal just can't show them). The fluid ghost slot that would have gated this now exists, so the interface hook's fluid `ghosts` row is unblocked too
6. ~~Stage 5 crafting~~ — done; patterns, the resolver, the job and the CPU are kind-generic, and a Crafting Tank holds the fluid side
7. Stage 5 warehouse — fluid gantry retrieval, which is what still blocks a fluid raw material coming off a shelf
