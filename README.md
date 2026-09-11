![Banner](https://cdn.modrinth.com/data/cached_images/9b7b261ed9e0e72297e71d27014df708c85cfe9d.png)

[![Modrinth downloads](https://badges.moddingx.org/modrinth/downloads/boilerplate-logistics?style=flat)](https://modrinth.com/project/boilerplate-logistics)
[![CurseForge downloads](https://badges.moddingx.org/curseforge/downloads/1691611?style=flat)](https://www.curseforge.com/minecraft/mc-mods/boilerplate-logistics)
[![MC versions](https://badges.moddingx.org/modrinth/versions/boilerplate-logistics?style=flat)](https://modrinth.com/project/boilerplate-logistics)

<!-- Badges from https://github.com/ModdingX/ModBadges. CurseForge takes the numeric project id,
     Modrinth the slug. The Modrinth ones report an error until that project is published. -->

**Boilerplate** is a steampunk pneumatic-tube logistics and storage mod, in the spirit of the
original Logistics Pipes.

Items, fluids and chemicals ride the same brass tubes, pushed along by compressed air. Storage is a
warehouse you build out of racks, with a gantry crane that walks it to fetch what you ask for.

---

## Everything is a hook

There is a **Pipe** and a **Glass Pipe** — the same tube, with the glass one letting you watch cargo
travel through it. Behaviour comes from **hooks**: small attachments fitted to a single *face* of a
pipe segment. Six faces, six independent hooks, one block.

| Hook | What it does |
| --- | --- |
| **Extraction** | Pulls from whatever it faces and sends it into the network. Acts on its own, on a timer you set. |
| **Filter** | Makes the face a routing destination, with a filter, a priority and a colour to match. |
| **Provider** | Marks the attached inventory as a source that network requests may pull from. |
| **Requester** | Holds standing orders and tops the attached inventory back up from the network. |
| **Sync** | A filtered destination and a filtered source on the same face. |
| **Interface** | A stocking reservoir with a pass-through face, and the seam between two subnets. |
| **Terminal** | A searchable window onto everything the network reaches, with withdraw and deposit. |
| **Crafting Terminal** | A terminal with a real 3×3 grid, fed straight from network stock. |
| **Pattern Terminal** | A terminal with a ghost grid, for authoring patterns. |
| **Pattern Provider** | Holds encoded patterns and runs them against the machine it faces. |
| **Adapter** | Joins a full-size item pipe to a slim pressure pipe. |

A **Brass Wrench** or **Diamond Wrench** harvests every Boilerplate block, and neither ever wears
out — an infrastructure tool is one you keep in the hotbar.

## Filters are cards you compose

A filter is a **Filter Card**, written once and usable anywhere a filter goes — an extraction hook,
a filter hook, a provider, a requester. Six kinds:

- **Resource** — one exact item, fluid or chemical, matched with or without its data components.
- **Tag** — everything in a tag.
- **Mod** — everything from one mod.
- **Regex** — names matching a pattern.
- **Colour** — cargo carrying a particular consignment colour.
- **Combined** — AND / OR / NOT over other cards, which may themselves be combined cards.

Cards nest, so "anything tagged `c:ores`, from this mod, and dyed blue" is one card holding three
others.

## Routing you can direct

- **Consignment colours.** An extraction hook stamps a dye colour on everything it sends, and a
  destination can require a matching one — two runs share the same pipes and stay separate.
- **Priority.** Destinations are ranked, with distance breaking ties, so "fill the machine, then
  the overflow barrel" is a pair of numbers. An extractor can spread its pulls round-robin across
  equally ranked destinations, or keep feeding the best one until it fills.

Two networks meeting at an **Interface Hook** stay separate — a **subnet**, bridged through that
hook's own stock. Routing, requests and terminal reachability each stop at the seam, so a
processing loop keeps its own working stock to itself.

## The warehouse is a building

Mark out a volume with the **Warehouse Planner** — two corners and a click on the **Warehouse
Controller** — and a **Gantry Rail** frame goes up around it. Fill that space with storage, and the
crane travels to each block to fetch what a terminal or a request asks for.

A rack is simply anything that holds things. Chests, barrels, another mod's drawers, tanks and
machine buffers all index and serve exactly like Boilerplate's own racks — the warehouse looks at
every block inside the bounds and takes whatever storage each one exposes, per face and per
resource kind, so a tank offering items on one side and chemicals on another is indexed for both.

The mod's own racks are shaped for particular jobs:

- **General Rack** — ordinary shelving, closest to a chest.
- **Bulk Rack** — a very large quantity of one resource.
- **Unstackable Rack** — enchanted gear, written books and similar; identical copies collapse into
  a single record with a count.
- **Distributed Multi Buffer** — one stack-denominated pool shared by every counted resource, so
  each one costs its own share of a stack: 64 cobblestone, 16 ender pearls, one shulker box.
- **Distributed Multi Tank** — the same pool in buckets, for fluids and chemicals. A millibucket
  each of a thousand fluids costs one bucket of room.
- **Omnibuffer** — both pools at once, for a process handling a mixture.

A standalone **Fluid Tank** block holds 16 buckets, and serves as a rack or anywhere else on the
network that storage is wanted.

## Patterns and Crafting CPUs

Encode a **Blank Pattern** at a Pattern Terminal as either a **Crafting Pattern**, matched against
a real recipe, or a **Processing Pattern**, an unordered bag of inputs and outputs — so
`1000mB water + clay → slurry` is a single pattern.

Crafting happens in the pipes. A **Crafting Buffer** encasement wraps a pipe segment, and adjacent
encased segments form a shared-capacity **Crafting CPU** when they make up a complete cuboid. The
CPU claims a plan's raw materials up front, ships each step out to whatever Pattern Provider can
run it, and pulls the result back. Your furnaces and machines do the work; the pipes do the
fetching.

## Compressed air runs it

**Compressor** and **Pressure Tank** encasements feed a **Pressure Pipe** network, and everything
downstream draws from it:

- A pipe run carries cargo while it has air behind it, and carries it faster the more it has. A run
  that loses its supply holds what it has until the pressure returns, and the run feeding it backs
  up at the seam.
- Each hook draws pressure to stay online, and shows its state on the block: a hook with supply
  reads as running, one without reads as idle, so a glance along a run finds where the pressure
  ran out.
- The gantry crane and Crafting CPUs move at a speed set by what is available.

A terminal shows what it can currently reach and act on, which for an unpowered network is nothing.

## One system, every resource

Items, fluids, and — on NeoForge with Mekanism installed — chemicals travel the same pipes, fill
the same racks, list in the same terminal, and go into the same patterns and filter cards. Resource
kinds come from a registry, so an addon can add its own and have it work across all of that.

## Recipe viewers

**JEI**, **REI** and **EMI** each get the same support. Every row in a terminal is right-clickable
for recipes, whether it holds an item, a fluid or a chemical. The transfer button fills a Crafting
Terminal's grid from network stock, and fills a Pattern Terminal's ghost grid from any recipe at
all, setting the pattern to crafting or processing to suit it.

## How it fits together

An extraction hook on a chest, a run of pipe, a filter hook on a furnace and a compressor to drive
it is a working system. Add a warehouse and a terminal hook as the chests multiply; patterns and a
Crafting CPU to have the machines fed for you; an interface hook to give one corner of the base its
own stock.

Pipe speed, extraction batch size, how much may queue at a destination and the rest are all in the
config screen, and the settings belonging to a particular hook can be set on that hook's own GUI.

---

## Requirements

- Minecraft **1.21.1**
- **Fabric** or **NeoForge**
- [Architectury API](https://modrinth.com/mod/architectury-api),
  [Archie](https://github.com/kernel-panic-codecave/Archie),
  [Cloth Config](https://modrinth.com/mod/cloth-config) — plus Fabric API and Fabric Language
  Kotlin on Fabric

Optional: JEI, REI or EMI for recipe lookup; Mekanism (NeoForge) for chemical support.

## Status

Pre-release. Every system described above is built and covered by tests, and everything is
available from the creative tab — crafting recipes, balance and the final block palette are still
being worked out, so this is one for creative worlds and feedback.

Bug reports and design arguments are equally welcome on the
[issue tracker](https://github.com/kernel-panic-codecave/Boilerplate/issues).

## Artist wanted

I'm looking for someone willing to take on the mod's textures and models — pipes, hooks, racks, the
gantry, the GUI, all of it. If a steampunk pneumatic aesthetic sounds like your kind of thing, open
an issue on the [tracker](https://github.com/kernel-panic-codecave/Boilerplate/issues) and let's
talk.

## Links

- [Source](https://github.com/kernel-panic-codecave/Boilerplate)
- [Issues](https://github.com/kernel-panic-codecave/Boilerplate/issues)

Licensed under [GPL-3.0-or-later](LICENSE.md).
