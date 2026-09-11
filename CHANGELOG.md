# Changelog

All notable changes to Archie are documented here, generated automatically from merged pull requests and direct commits following [Conventional Commits](https://www.conventionalcommits.org/).

## [0.1.0-alpha] - 2026-09-11

### Features

- implement M1 pipe network core ([587f60a](https://github.com/kernel-panic-codecave/Boilerplate/commit/587f60a0f10f135199db34ef7fe183d40b2c3ccd)) - KernelPanic
- implement M2 sorting and color-coded routing ([04e9e8b](https://github.com/kernel-panic-codecave/Boilerplate/commit/04e9e8b5da0d320337eb02137aa3ceea30d8f49e)) - KernelPanic
- **warehouse:** start M3 with the wand-bound volume ([41c8aee](https://github.com/kernel-panic-codecave/Boilerplate/commit/41c8aee8689e7ac6e7c4bf57de4faae0c789789a)) - KernelPanic
- **warehouse:** rack scanning and WarehouseIndex ([8b3b1ea](https://github.com/kernel-panic-codecave/Boilerplate/commit/8b3b1ea76b7ae0719799d991d36a236529bb517f)) - KernelPanic
- **warehouse:** gantry motion model, sync, and ghost rail rendering ([da38e86](https://github.com/kernel-panic-codecave/Boilerplate/commit/da38e86fce734c8840d9ea24c3c269e5f2b3a017)) - KernelPanic
- **warehouse:** job queue and controller staging buffer ([b5cd9f9](https://github.com/kernel-panic-codecave/Boilerplate/commit/b5cd9f97812afb22f6dc1ab3afabd54d9f16927e)) - KernelPanic
- **warehouse:** requester/provider hooks and targeted routing ([7d1e367](https://github.com/kernel-panic-codecave/Boilerplate/commit/7d1e367715b90f0ee7e5dba8e6b14a171e62f5af)) - KernelPanic
- **routing:** default route via a reserved sorting-hook priority ([a8cdc25](https://github.com/kernel-panic-codecave/Boilerplate/commit/a8cdc251641a8bc43b61626becfd9c8c54a2710d)) - KernelPanic
- **warehouse:** real gantry rail frame + moving crossbeam renderer ([1d41e60](https://github.com/kernel-panic-codecave/Boilerplate/commit/1d41e603893af7a66a3830bd4d120d72e396fa2e)) - KernelPanic
- **warehouse:** real per-corner AO on the clipped drop rod segment ([a3017be](https://github.com/kernel-panic-codecave/Boilerplate/commit/a3017bed05827a8bbfb606df3019349a56f8c8a5)) - KernelPanic
- **warehouse:** always render the gantry arm, not just mid-move ([d5f63dd](https://github.com/kernel-panic-codecave/Boilerplate/commit/d5f63dd714da57a0fa83a5ab04d3078063693743)) - KernelPanic
- **warehouse:** split inbound/outbound buffers, batch the gantry's carry ([f0e59c5](https://github.com/kernel-panic-codecave/Boilerplate/commit/f0e59c5c035c0b824aeec959feaa9c7c86b565c4)) - KernelPanic
- **warehouse:** warehouse terminal hook with network-wide search/withdraw ([b538e24](https://github.com/kernel-panic-codecave/Boilerplate/commit/b538e2464156ce38a98ffe86b5b07db682e22aef)) - KernelPanic
- **pipe:** exclude terminal's bare inventory connection from push routing ([583e8c5](https://github.com/kernel-panic-codecave/Boilerplate/commit/583e8c5e364ef53e8ab7320b381ef68b878a2362)) - KernelPanic
- **warehouse:** quantity-picker modal + immediate UI update for terminal withdrawals ([08ec57f](https://github.com/kernel-panic-codecave/Boilerplate/commit/08ec57f73232e643a6993aaeaf79b9456e60ea7f)) - KernelPanic
- **pipe:** split pipe/glass pipe models into core+arm+straight, hand-authored ([5096e52](https://github.com/kernel-panic-codecave/Boilerplate/commit/5096e522c901b9568636df495927602779a0fab3)) - KernelPanic
- **pipe:** rework warehouse terminal into a per-face hook GUI ([053f70f](https://github.com/kernel-panic-codecave/Boilerplate/commit/053f70fc477f07f3f3c895a41e7f28c29346ba33)) - KernelPanic
- **pipe:** add filter card condition system ([74d9e0f](https://github.com/kernel-panic-codecave/Boilerplate/commit/74d9e0f454e9f41f4ce9ebfce78255aff297d5a6)) - KernelPanic
- **warehouse:** gantry visuals, incremental index rebuild, and a defrag pass ([325f6cb](https://github.com/kernel-panic-codecave/Boilerplate/commit/325f6cb2f93d4b261aae20726717cc621a8aa114)) - KernelPanic
- **warehouse:** add bulk/general/unstackable rack block types ([35230d7](https://github.com/kernel-panic-codecave/Boilerplate/commit/35230d7b4c41376e3b7750ee980fe56a0d0b03c5)) - KernelPanic
- **pipe:** add subnet-boundary system + interface hook ([eec5938](https://github.com/kernel-panic-codecave/Boilerplate/commit/eec59381abd96318dc8d641775a01dce79cbb802)) - KernelPanic
- **crafting:** add M4 pattern representation + assembly table encode GUI ([31cd1df](https://github.com/kernel-panic-codecave/Boilerplate/commit/31cd1dffc29c03ae940c84bccb1eac4e582f1448)) - KernelPanic
- **crafting:** add the DAG crafting resolver ([461beee](https://github.com/kernel-panic-codecave/Boilerplate/commit/461beee98e2e337d36a640824f5f20bc57c7ca2f)) - KernelPanic
- **crafting:** make the assembly table actually process patterns over time ([3fb3f6a](https://github.com/kernel-panic-codecave/Boilerplate/commit/3fb3f6a22de0f057218bf9103c9e466d6c33448c)) - KernelPanic
- **crafting:** add a terminal Craft tab that resolves and executes on-demand requests ([0118b51](https://github.com/kernel-panic-codecave/Boilerplate/commit/0118b51c1d25ec512ee224e9eb622eb58fba2609)) - KernelPanic
- **crafting:** add the Pattern item, blank and encoded ([13f2fb2](https://github.com/kernel-panic-codecave/Boilerplate/commit/13f2fb222d3f092ce6cb573721b0258ccdc4984d)) - KernelPanic
- **crafting:** decouple patterns from the assembly table via a Pattern Provider hook ([875400d](https://github.com/kernel-panic-codecave/Boilerplate/commit/875400db73fc2814993e703fcbd9e368c5fe75d4)) - KernelPanic
- **crafting:** give the terminal its own built-in output slots ([fb7ce36](https://github.com/kernel-panic-codecave/Boilerplate/commit/fb7ce36ce449bbd0634c61a0a764e2285974e990)) - KernelPanic
- **pipe:** make the interface hook an active network participant ([5b2b86c](https://github.com/kernel-panic-codecave/Boilerplate/commit/5b2b86c83932b450870ccc3b91b67c55ce614867)) - KernelPanic
- **crafting:** add the Crafting Terminal hook ([6ba4b5a](https://github.com/kernel-panic-codecave/Boilerplate/commit/6ba4b5a3655bd43e5ce3e06e11e145b6340be511)) - KernelPanic
- **crafting:** add the Pattern Terminal hook ([ad0fa34](https://github.com/kernel-panic-codecave/Boilerplate/commit/ad0fa34030658512815196616e43926797ef0507)) - KernelPanic
- **crafting:** node-based, pannable crafting job tree view ([59c373e](https://github.com/kernel-panic-codecave/Boilerplate/commit/59c373e7991db52ed28e156e8b4b42e2a5bfdab1)) - KernelPanic
- **crafting:** rework terminal-family Store tab into an AE2-style unified grid ([b297c92](https://github.com/kernel-panic-codecave/Boilerplate/commit/b297c9251eca34b2f2db043243330fd7cfaefd27)) - KernelPanic
- **crafting:** merge Crafting/Pattern Terminal UX, node-based job tree ([ead8c6a](https://github.com/kernel-panic-codecave/Boilerplate/commit/ead8c6abee473677b585fcfc4b84da2e49999268)) - KernelPanic
- **crafting:** per-pattern virtual input buffers + parallel Assembly Table runs ([e0aef2f](https://github.com/kernel-panic-codecave/Boilerplate/commit/e0aef2f6c5a9999cb40e682104ae651b94ca8666)) - KernelPanic
- **pipe:** unify hooks and pipe segments into a Multipart block with a generic hook/encasement attachment system ([8b96f6f](https://github.com/kernel-panic-codecave/Boilerplate/commit/8b96f6fa0fda35e89d62723255f0892d5415a075)) - KernelPanic
- **crafting:** replace the Assembly Table with a Crafting CPU/buffer multiblock ([6400e95](https://github.com/kernel-panic-codecave/Boilerplate/commit/6400e95b9631a3e060a30acdc707cd10af0d29ab)) - KernelPanic
- **power:** add the M5 pressure network, capability, and casing/manager unification ([91707cd](https://github.com/kernel-panic-codecave/Boilerplate/commit/91707cdf3a28430b412de14c40f7e3c0a78e93d3)) - KernelPanic
- **pipe:** gate every hook behind reachable pressure, add an on/off model and a creative pressure source ([9aee87c](https://github.com/kernel-panic-codecave/Boilerplate/commit/9aee87cf26760ef83385af5bff3bdfc7fd2b6bde)) - KernelPanic
- **power:** make pressure a hard gate everywhere it's consumed, not just a hook-only bonus ([db36734](https://github.com/kernel-panic-codecave/Boilerplate/commit/db36734aae92b559d16d63e7b6fa49f58fd08921)) - KernelPanic
- **art:** bespoke textures for the compressor, pressure tank, and crafting buffer casings, and the pressure pipe ([d4b070d](https://github.com/kernel-panic-codecave/Boilerplate/commit/d4b070d63e6592df914d93b4eb0543646a5b69a5)) - KernelPanic
- **warehouse:** model and texture the three rack types ([50ac227](https://github.com/kernel-panic-codecave/Boilerplate/commit/50ac2271d0f17ae7477aa0af419ef07d957d4561)) - KernelPanic
- wire up Archie's JUnit bridge for the GameTest suite ([b1598ee](https://github.com/kernel-panic-codecave/Boilerplate/commit/b1598ee834492beb95d401f43f916a875b60ed06)) - KernelPanic
- **art:** add mod icon, banner, and title art, wire into loader metadata ([825c44a](https://github.com/kernel-panic-codecave/Boilerplate/commit/825c44a44b4ad9ad2e4fbf224e956cf4237bbbf7)) - KernelPanic
- **compat:** add BoilerplateREIPlugin - crafting-terminal recipe transfer ([ff46322](https://github.com/kernel-panic-codecave/Boilerplate/commit/ff463226925539f097d5006c6551d1d578211f15)) - KernelPanic
- **compat:** add BoilerplateJEIPlugin, correct its dependency shape ([69a4295](https://github.com/kernel-panic-codecave/Boilerplate/commit/69a4295ef99e3b036051cf704f290dd23871c724)) - KernelPanic
- **compat:** add BoilerplateEmiPlugin - fabric and neoforge each ([07addc6](https://github.com/kernel-panic-codecave/Boilerplate/commit/07addc692718605bb053e59d0995445f91699a49)) - KernelPanic
- **crafting:** let the terminal grid pull ingredients on shift/ctrl-click ([91f2d19](https://github.com/kernel-panic-codecave/Boilerplate/commit/91f2d19ec6ca015a68bb96bdb129c4e699687da4)) - KernelPanic
- **compat:** let recipe-viewer fill pull from storage and the inbox ([bab3e5f](https://github.com/kernel-panic-codecave/Boilerplate/commit/bab3e5f3ca9073c3589f8d813c611a98f6584b7e)) - KernelPanic
- **pipe:** instant provider delivery with a cosmetic ghost TravelingItem ([dc4d259](https://github.com/kernel-panic-codecave/Boilerplate/commit/dc4d259df94f50f1b35cd84cd222fa4b93b64f14)) - KernelPanic
- **compat:** tri-color EMI missing-ingredient highlight ([139e1ee](https://github.com/kernel-panic-codecave/Boilerplate/commit/139e1ee90f967760606e6c54d3a230297353feda)) - KernelPanic
- **compat:** bulk-aware supply requests + tri-color highlight on REI/JEI ([811b38a](https://github.com/kernel-panic-codecave/Boilerplate/commit/811b38a7bf4b365700ffb02c46db15d1f488b4d7)) - KernelPanic
- **pipe:** reservation tracking + cancel-redirect for terminal deliveries ([d6047b0](https://github.com/kernel-panic-codecave/Boilerplate/commit/d6047b0b6c94e5a403e251419cbf720c1a0f67bb)) - KernelPanic
- **pipe:** wire reservation creation into withdraw and supplyIngredients ([a01ccff](https://github.com/kernel-panic-codecave/Boilerplate/commit/a01ccff4fd84a0b3fbb110a2fe05de770b1b2f95)) - KernelPanic
- **pipe:** sync pending deliveries to the client and render them ([1dbde39](https://github.com/kernel-panic-codecave/Boilerplate/commit/1dbde39ae2c24d4a0c6d76a3f5b705ca17a542fd)) - KernelPanic
- **pipe:** reserve a real inbox slot, and refuse what won't fit ([856fe11](https://github.com/kernel-panic-codecave/Boilerplate/commit/856fe113cd503b42f8cccccb9374c2df36eae1ab)) - KernelPanic
- **pipe:** item travel speed scales with available pressure ([a049e99](https://github.com/kernel-panic-codecave/Boilerplate/commit/a049e998ad8df39f9a129e4a940a101c0dbf394c)) - KernelPanic
- **pipe:** reserved-slot countdown is a rolling estimate ([bd9d2b1](https://github.com/kernel-panic-codecave/Boilerplate/commit/bd9d2b182f50640aa787daa44c9a0943f0eb4dc1)) - KernelPanic
- **pipe:** F3+B debug network overlay ([85c2c5e](https://github.com/kernel-panic-codecave/Boilerplate/commit/85c2c5e11c2a36633c003fbf08aa54d92a6158bd)) - KernelPanic
- **warehouse:** rack shelves with routing filters and a controller menu ([f124a46](https://github.com/kernel-panic-codecave/Boilerplate/commit/f124a460f017c98e9d7c8ad75d92b50c4f1f49f5)) - KernelPanic
- fluid parity, warehouse debug overlay, and crafting fixes ([0b96e4a](https://github.com/kernel-panic-codecave/Boilerplate/commit/0b96e4a907dad45d95f9edf37c431676f51ce90b)) - KernelPanic
- **crafting:** fluids in processing patterns, and a Crafting Tank ([b05b186](https://github.com/kernel-panic-codecave/Boilerplate/commit/b05b186b30a7456dce7fd2957ea484805f301c84)) - KernelPanic
- **warehouse:** make storage resource-kind agnostic ([ac8e20a](https://github.com/kernel-panic-codecave/Boilerplate/commit/ac8e20ad1ac76b8068111a3863c512a12765ac51)) - KernelPanic
- **hooks:** stocking rows for the requester and interface, and a real requester GUI ([efb9c4a](https://github.com/kernel-panic-codecave/Boilerplate/commit/efb9c4aeffc9c17064572f409e7f65d5672793ab)) - KernelPanic
- **resources:** make every system kind-generic, so registering a kind is enough ([8f147dc](https://github.com/kernel-panic-codecave/Boilerplate/commit/8f147dc0ece9e0fb0a49b3b7c0352def13143ebb)) - KernelPanic
- **config:** move the tunable constants onto Archie's config system ([9dfeb4a](https://github.com/kernel-panic-codecave/Boilerplate/commit/9dfeb4a75004729177feede9319dfed233ab116b)) - KernelPanic
- **pipe,warehouse,gui:** subnet boundaries, pooled racks, and a large pass on network performance ([d0ecac6](https://github.com/kernel-panic-codecave/Boilerplate/commit/d0ecac6f0a3a35ad4a7b03ec39002ecd9499c12d)) - KernelPanic
- **pipe,gui:** in-flight delivery accounting, terminal sort and search, declared tooltips ([06f95fa](https://github.com/kernel-panic-codecave/Boilerplate/commit/06f95fa29810145e972c08e439106df89ca44649)) - KernelPanic
- **pipe,compat:** pattern fill from any recipe viewer, dead networks stop, README ([60fef99](https://github.com/kernel-panic-codecave/Boilerplate/commit/60fef99c7c825ae021c121caea5ed0686c920c74)) - KernelPanic

### Bug Fixes

- add cloth-config to unblock boot crash from Archie's config init ([01cc0e2](https://github.com/kernel-panic-codecave/Boilerplate/commit/01cc0e25b7f7125e9a8745dca89b242dcfc3c2d8)) - KernelPanic
- stop extractor routing items back to their own source ([1ced62b](https://github.com/kernel-panic-codecave/Boilerplate/commit/1ced62b51d68bc26d705d591c57472b25a38f600)) - KernelPanic
- stop discarding traveling-item progress every tick ([6d255b0](https://github.com/kernel-panic-codecave/Boilerplate/commit/6d255b0577be635a53da55aa9aaa426577ed4230)) - KernelPanic
- **warehouse:** NeoForge crash from resolving a RegistrySupplier too early ([ccc88bb](https://github.com/kernel-panic-codecave/Boilerplate/commit/ccc88bb6e25107ee73d5d15f980bf1b2e8b08f40)) - KernelPanic
- **warehouse:** wand's own sound feedback was inaudible to the player using it ([3d267f5](https://github.com/kernel-panic-codecave/Boilerplate/commit/3d267f5e351a25749568538700a7cda4f3b2a80f)) - KernelPanic
- **warehouse:** drop stale index entries and return the gantry home ([ef5bed8](https://github.com/kernel-panic-codecave/Boilerplate/commit/ef5bed8e7a5ae320eb35bbfa34e192ed128b4646)) - KernelPanic
- **warehouse:** cap crossbeam/rod segment ends instead of showing an arm stub ([e0ee1e2](https://github.com/kernel-panic-codecave/Boilerplate/commit/e0ee1e2453d477b0e99b434b036c29d8a31e5d14)) - KernelPanic
- **warehouse:** smooth drop rod growth and stop it culling off-screen ([4c7a9f1](https://github.com/kernel-panic-codecave/Boilerplate/commit/4c7a9f1d31a5232749262eb610e07e5c8f72c0a7)) - KernelPanic
- **warehouse:** fix off-screen culling on NeoForge via a mixin ([5ccfd0b](https://github.com/kernel-panic-codecave/Boilerplate/commit/5ccfd0b6a246fffec3b5169e959c13e3da2c41dc)) - KernelPanic
- **build:** make Compose runtime's transitive deps win NeoForge's dev-run classloading ([c90076f](https://github.com/kernel-panic-codecave/Boilerplate/commit/c90076f8d8629e08a5c6828b5c28bb6524bf0277)) - KernelPanic
- face the warehouse terminal's plate away from the pipe core ([dc9c229](https://github.com/kernel-panic-codecave/Boilerplate/commit/dc9c229a9ca2a6f84e5bcfe684bdd41993d2c61e)) - KernelPanic
- **warehouse:** stop put-away cycling items through a full rack, prefer nearest empty one ([50793a3](https://github.com/kernel-panic-codecave/Boilerplate/commit/50793a3fd8fefe38c57ba17660f6a913ded8bd43)) - KernelPanic
- **crafting:** use Archie's TabContainerPanel for the terminal's Store/Craft tabs ([64a9b23](https://github.com/kernel-panic-codecave/Boilerplate/commit/64a9b23e8fdeffeb1f20c74cf3c3cb1d6ee8fb89)) - KernelPanic
- **crafting:** give the Craft tab its own craftable-resource catalog ([cb0ebd0](https://github.com/kernel-panic-codecave/Boilerplate/commit/cb0ebd0b513b7d3ef8734376d873a44bf8beea43)) - KernelPanic
- **crafting:** open the assembly table menu through MenuRegistry.openExtendedMenu ([8d25223](https://github.com/kernel-panic-codecave/Boilerplate/commit/8d25223d87d14ec88902f878358fc28b39c85049)) - KernelPanic
- **pipe:** resolve deliveries against the specific hook face, not pipe-arrival topology ([8258d56](https://github.com/kernel-panic-codecave/Boilerplate/commit/8258d562206637a3123f505c6f201955855d4bf0)) - KernelPanic
- **gui:** abbreviate oversized item counts in terminal slots ([aff2611](https://github.com/kernel-panic-codecave/Boilerplate/commit/aff2611fe4d7242bef4aaca378074dd2083c64ee)) - KernelPanic
- **crafting:** restore CraftingJob.hookFaceForStep dropped while splitting commits ([ebec647](https://github.com/kernel-panic-codecave/Boilerplate/commit/ebec64705a0a5cdc95b4ac5f5e77ee3ff74113f4)) - KernelPanic
- **crafting:** restore the face-isolation regression test dropped while splitting commits ([68cdc49](https://github.com/kernel-panic-codecave/Boilerplate/commit/68cdc49642edc57e6708b0ccd2a0259890454da7)) - KernelPanic
- **crafting:** avoid Long-to-int truncation when probing a provider hook's real inventory stock ([af61379](https://github.com/kernel-panic-codecave/Boilerplate/commit/af61379212269b5678164406be2756a559c8d707)) - KernelPanic
- **power:** stop pressureless sources from being usable, and fix stale network topology ([a5d04ae](https://github.com/kernel-panic-codecave/Boilerplate/commit/a5d04ae7ed2b86b1e2d76507d96f308e33abb3f9)) - KernelPanic
- **compat:** stop recipe-viewer plugins crashing on the not-yet-laid-out grid ([eaeb98d](https://github.com/kernel-panic-codecave/Boilerplate/commit/eaeb98d53cbd1344a17f17e9691727ac3e4cba71)) - KernelPanic
- **compat:** stop EMI's own entrypoint annotation crashing Fabric's loader ([5750ec7](https://github.com/kernel-panic-codecave/Boilerplate/commit/5750ec7c976ad0937fcbebd6b3677553a03e0853)) - KernelPanic
- **compat:** land recipe-viewer ingredient supply in the terminal's inbox ([0f475b0](https://github.com/kernel-panic-codecave/Boilerplate/commit/0f475b09805693897be295e74b03126c73225269)) - KernelPanic
- **compat:** fire ingredient supply on every fill evaluation, not just a commit ([a996346](https://github.com/kernel-panic-codecave/Boilerplate/commit/a996346e8b03794251a6d386c9e67bfb5b041e16)) - KernelPanic
- **compat:** only fire ingredient supply on a genuine craft commit ([426c1b8](https://github.com/kernel-panic-codecave/Boilerplate/commit/426c1b85bde11917f358aac4e2cf02ef2a3dcdaf)) - KernelPanic
- **compat:** never list the crafting grid as its own ingredient source ([4d41f9c](https://github.com/kernel-panic-codecave/Boilerplate/commit/4d41f9c9d53cc3978c5ff1dba33d0aaa2d7de47b)) - KernelPanic
- **pipe:** land instant ingredient grants in the inbox, not the grid ([a3162bb](https://github.com/kernel-panic-codecave/Boilerplate/commit/a3162bbd20db18b91d656fd0c837e7a822b7c0e0)) - KernelPanic
- **pipe:** remove the instant ingredient grant, still too failure-prone ([dee6988](https://github.com/kernel-panic-codecave/Boilerplate/commit/dee698809758f962b453dc2dca60c5e671abad6f)) - KernelPanic
- **compat:** stop the same-targets debounce from blocking repeat requests ([78fedfc](https://github.com/kernel-panic-codecave/Boilerplate/commit/78fedfc1fc5c9d4c72e7317f3901dcb0504bc7f7)) - KernelPanic
- **compat:** let the fill button click when supply is only network-reachable ([9dc533b](https://github.com/kernel-panic-codecave/Boilerplate/commit/9dc533b069c18fe30cf78511e3e9fea5316ca4da)) - KernelPanic
- **gui:** shift-click from player inventory now goes to storage, not the grid ([db519a7](https://github.com/kernel-panic-codecave/Boilerplate/commit/db519a75b2d43cdc115214cddc7bae725af86aa5)) - KernelPanic
- **compat:** add the missing Fabric entrypoint for JEI plugin discovery ([527e39b](https://github.com/kernel-panic-codecave/Boilerplate/commit/527e39b667e18982fe99197042f8767b4e131796)) - KernelPanic
- **compat:** only skip REI's real fill when something needs fetching ([6855aa0](https://github.com/kernel-panic-codecave/Boilerplate/commit/6855aa0348f3f164f2fc3197594f4ea5a159d4fa)) - KernelPanic
- **compat:** check every alternative a slot accepts, not one fixed pick ([67f1db2](https://github.com/kernel-panic-codecave/Boilerplate/commit/67f1db2194b0b374384c7595bdbd70ebc0267326)) - KernelPanic
- **pipe:** carry reservationId across every pipe hop, not just the first ([2c909fa](https://github.com/kernel-panic-codecave/Boilerplate/commit/2c909fadcc796731898bc5e85e92b5877c476b03)) - KernelPanic
- **pipe:** stop cancelled redirects voiding items, plus two reservation bugs ([803bf25](https://github.com/kernel-panic-codecave/Boilerplate/commit/803bf251ecd887b1d1e61b385595f6b721ecad09)) - KernelPanic
- **warehouse:** never strand a retrieval in the outbound buffer ([5fda185](https://github.com/kernel-panic-codecave/Boilerplate/commit/5fda1858fb70f10025b617a774d0dd6c585434b3)) - KernelPanic
- **warehouse:** stop dropOff destroying anything it can't place ([8898ef7](https://github.com/kernel-panic-codecave/Boilerplate/commit/8898ef750a99743aa43563f8b0838e52eebc590f)) - KernelPanic
- **warehouse:** rack storage read a snapshot taken before NBT loaded ([241f03a](https://github.com/kernel-panic-codecave/Boilerplate/commit/241f03a080d2a42ea8b3240f09ae16c5772c4dbf)) - KernelPanic
- **warehouse,filter:** priority was inverted, filter cards saved in a shape nothing read ([cafbb15](https://github.com/kernel-panic-codecave/Boilerplate/commit/cafbb15097fe827369e5a1d8256748e6da9d623f)) - KernelPanic
- **gui:** reserved-slot previews come back when a terminal is reopened ([8c8082e](https://github.com/kernel-panic-codecave/Boilerplate/commit/8c8082e56b2c5913329de297f8f7e53b914a0496)) - KernelPanic
- **gui:** reserved-slot placeholders gated on an unobservable, cross-thread read ([8c846b9](https://github.com/kernel-panic-codecave/Boilerplate/commit/8c846b9d550bb4062187a7c0d8da50d15c7cc802)) - KernelPanic
- **warehouse:** carried items stuck on the gantry head, and sized for the wrong head ([abcb02f](https://github.com/kernel-panic-codecave/Boilerplate/commit/abcb02f5a58cffd2403bebb4578ba1915cf6c01d)) - KernelPanic
- **warehouse:** centre carried items on the gantry head ([15806ff](https://github.com/kernel-panic-codecave/Boilerplate/commit/15806ff5742a3b16778ca8138c636f82e383cb2b)) - KernelPanic
- **warehouse:** carried items placed at the head's corner in the Flywheel visual ([0a7cd3c](https://github.com/kernel-panic-codecave/Boilerplate/commit/0a7cd3c1d4801c9d342c7b3409c12bdbce666899)) - KernelPanic
- **warehouse:** gantry head left parked at rail height after a retrieval ([d506672](https://github.com/kernel-panic-codecave/Boilerplate/commit/d506672e14814f801134402403ec800bf43f2c50)) - KernelPanic
- **gui:** shared ingredient laid out one column too shallow in the job tree ([defb3b2](https://github.com/kernel-panic-codecave/Boilerplate/commit/defb3b2ec42c42b12c1d31ad73819fb975844a67)) - KernelPanic
- **gui:** nest a shared job-tree ingredient under its deepest consumer ([088f728](https://github.com/kernel-panic-codecave/Boilerplate/commit/088f72857882e9df2e27baba0f6841276b0979f4)) - KernelPanic
- **pipe:** contain interface recursion; face- and ghost-aware hook delivery ([c01e315](https://github.com/kernel-panic-codecave/Boilerplate/commit/c01e31519972d5940fb883db5af90fd1d7bd8851)) - KernelPanic
- **pipe:** game-tick-anchored travel and synced gantry speed ([b7cdce8](https://github.com/kernel-panic-codecave/Boilerplate/commit/b7cdce8f18d781b42020104b63a295813ce2418d)) - KernelPanic
- **neoforge:** stamp FMLModType on Compose's transitive libraries ([1821db6](https://github.com/kernel-panic-codecave/Boilerplate/commit/1821db69b503a1b4f2b81459f990c79026a482ef)) - KernelPanic
- **warehouse:** persist empty racks in the index snapshot ([74305c4](https://github.com/kernel-panic-codecave/Boilerplate/commit/74305c4a7cb210881a2f2df2c5f3d12d9ef4f44b)) - KernelPanic
- **warehouse:** expose the controller as a fluid destination ([d7ae448](https://github.com/kernel-panic-codecave/Boilerplate/commit/d7ae448eab46e7b1425a8318661adde8de9c9111)) - KernelPanic
- **hooks:** a requester does nothing for an interface on its own subnet ([35fe956](https://github.com/kernel-panic-codecave/Boilerplate/commit/35fe956a8d6c09fc812337fb9d9088cf6bd87059)) - KernelPanic
- **pipe:** don't let a typeless tag take a multipart's whole block entity down ([7f1f68f](https://github.com/kernel-panic-codecave/Boilerplate/commit/7f1f68f9f2662ab0468cfedac1b19b6913f48fec)) - KernelPanic

### Refactoring

- rewrite PipeRouter.search as a tailrec function ([b1503b6](https://github.com/kernel-panic-codecave/Boilerplate/commit/b1503b65904eaf39d1eb535b6c8ce212c46a2c73)) - KernelPanic
- **pipe:** make PipeHookType generic over its own state type ([3f6fa3f](https://github.com/kernel-panic-codecave/Boilerplate/commit/3f6fa3fa64fbe5bfe66b94afb10a2b12d3097602)) - KernelPanic
- **pipe:** dispatch hook menus through PipeHookType.createMenu ([0424aef](https://github.com/kernel-panic-codecave/Boilerplate/commit/0424aef14a91bd97fb07d16919f40ce97e83403a)) - KernelPanic
- **pipe:** rename sorting hook to filter hook, add sync/terminal hook types ([593430e](https://github.com/kernel-panic-codecave/Boilerplate/commit/593430e7968cf7131ae3372afe3c5dfacd5cc432)) - KernelPanic
- **warehouse:** resolve the gantry head model through WarehouseControllerVisual ([4d83913](https://github.com/kernel-panic-codecave/Boilerplate/commit/4d83913ae2881962e3d001dae4c01a7561f642b5)) - KernelPanic
- generalize hook/encasement capability exposure ([7a1a4b3](https://github.com/kernel-panic-codecave/Boilerplate/commit/7a1a4b328b0b5f819a4cae16c29db795f930d779)) - KernelPanic
- use Archie's typed field helpers, drop stale gametest/datagen comments ([2b6402e](https://github.com/kernel-panic-codecave/Boilerplate/commit/2b6402e8bf1a2624ad0d6065642f2999651828bb)) - KernelPanic
- **compat:** move BoilerplateEmiPlugin into common via emi-xplat ([2167b11](https://github.com/kernel-panic-codecave/Boilerplate/commit/2167b11f73bd474cb986d74f406086639080e2c6)) - KernelPanic
- **compat:** thread the real requested amount, not just a bulk flag ([b76674e](https://github.com/kernel-panic-codecave/Boilerplate/commit/b76674e005c9d5593f0cbd7fc955dac8b31f1386)) - KernelPanic
- **hook:** sorting-hook filter is one real filter-card slot, not a ghost grid ([342e4bd](https://github.com/kernel-panic-codecave/Boilerplate/commit/342e4bd64c904a14b2f9bb2d63607c7e41b9c465)) - KernelPanic
- **warehouse:** one world-space convention for the gantry's traverse height ([8b316db](https://github.com/kernel-panic-codecave/Boilerplate/commit/8b316dbb34a7d61adfd793cf6b05ffc19bd05905)) - KernelPanic
- **pipe:** shared theme, clickable ghost slots, lazy hook types ([98af252](https://github.com/kernel-panic-codecave/Boilerplate/commit/98af2525c67f0284253ce18e43e5eb13c70a4734)) - KernelPanic
- **common:** doc hygiene and structural reorg across warehouse, hooks, power ([3df35ac](https://github.com/kernel-panic-codecave/Boilerplate/commit/3df35acaf90e013e97667b53509ea83bd5556ed2)) - KernelPanic
- clear IDE inspection findings from the fluid-parity work ([7bca64b](https://github.com/kernel-panic-codecave/Boilerplate/commit/7bca64b2846014444b63501f12cf87e5b7199c2d)) - KernelPanic

### Documentation

- confirm NeoForge Kotlin-lib mechanism is JarJar, not classpath magic ([7f0c969](https://github.com/kernel-panic-codecave/Boilerplate/commit/7f0c969840fec5efc8aff3a5debc76d1f40db249)) - KernelPanic
- add implementation-level design docs for M1-M6 ([6c9c88e](https://github.com/kernel-panic-codecave/Boilerplate/commit/6c9c88ef60f1d906395b33b432aefa0655c1ff10)) - KernelPanic
- **warehouse:** design request-based routing, drop WarehouseInterfaceBlock ([46c7feb](https://github.com/kernel-panic-codecave/Boilerplate/commit/46c7feb2f62556dbbfb2ab3d058414169dc9ff52)) - KernelPanic
- **routing:** note future subnet-boundary idea for hook-facing-hook ([6dbfced](https://github.com/kernel-panic-codecave/Boilerplate/commit/6dbfcedd67e17ed6f5f18f801393575cb03ea295)) - KernelPanic
- update m3 terminal design to match hook-based, pipe-delivery implementation ([48041f0](https://github.com/kernel-panic-codecave/Boilerplate/commit/48041f0015383ef40bfd69a1b777ecad6f72f55f)) - KernelPanic
- update design docs for the filter/sync/terminal hook rename, racks, defrag, and subnet boundaries ([bf1de5a](https://github.com/kernel-panic-codecave/Boilerplate/commit/bf1de5ab22400dbb7f667c250fa701e67f03cb7f)) - KernelPanic
- refresh design docs for the Multipart/Crafting CPU rewrite ([4e87cc9](https://github.com/kernel-panic-codecave/Boilerplate/commit/4e87cc92059aba0970538b2ea22cccd7eda884de)) - KernelPanic
- **design:** document the M5 pressure power layer ([6b307c9](https://github.com/kernel-panic-codecave/Boilerplate/commit/6b307c980036cb945b39a4932849ee678f33ae4c)) - KernelPanic
- **design:** expand M6 recipe-viewer scope to JEI/REI/EMI, wire dependencies ([3c35e2b](https://github.com/kernel-panic-codecave/Boilerplate/commit/3c35e2b9828350d1c3e11d6930936403e822c256)) - KernelPanic
- **m6:** document the reserved-slot placeholder feature ([1c70dd7](https://github.com/kernel-panic-codecave/Boilerplate/commit/1c70dd7ff2accd2971dade65eb0c07f85f57fa78)) - KernelPanic
- refresh sorting-routing, warehouse, and polish design docs ([5d19167](https://github.com/kernel-panic-codecave/Boilerplate/commit/5d19167eb1755cdbcd3ae7bcfbe5d05c0202c5de)) - KernelPanic

### Tests

- add GameTest infrastructure gated behind AGameTestPlatform.isGameTest ([674dbb5](https://github.com/kernel-panic-codecave/Boilerplate/commit/674dbb529fcc616b07c307ccb6c67963e32bcd67)) - KernelPanic
- **crafting:** cover CraftingResolver.maxCraftable + polish M4 design doc ([10ac3fd](https://github.com/kernel-panic-codecave/Boilerplate/commit/10ac3fd3ba0e309e797f75b3ffaf7361b4fee50e)) - KernelPanic
- **crafting:** add GameTest coverage for the Crafting CPU rewrite ([601eebb](https://github.com/kernel-panic-codecave/Boilerplate/commit/601eebb45ef4448e627aa04394393dd1366d237a)) - KernelPanic
- **power:** add GameTest coverage for the M5 pressure system ([5253e8d](https://github.com/kernel-panic-codecave/Boilerplate/commit/5253e8d175955440bfa91db5b3b834ad9b4deb2e)) - KernelPanic

### Build System

- register a client-side ModelBakeryMixin and bump deps ([f067c88](https://github.com/kernel-panic-codecave/Boilerplate/commit/f067c88e5ba93d2179f8a75d6d4b35563abdc498)) - KernelPanic
- bump NeoForge; drop Compose copy deps; add issue tracker; stack recipe viewers ([638067e](https://github.com/kernel-panic-codecave/Boilerplate/commit/638067e5a21df0809ca3a17ba1a0d664c3a6422e)) - KernelPanic
- **publish:** modpublisher, mirroring Archie's own setup ([e377175](https://github.com/kernel-panic-codecave/Boilerplate/commit/e37717579604ac96ae53bfdbd81d3190c88e28c8)) - KernelPanic
- **publish:** point at the real CurseForge project ([05aae88](https://github.com/kernel-panic-codecave/Boilerplate/commit/05aae883eee88e5d64080ecf31d4a57b579e5e83)) - KernelPanic
- **publish:** release-notes workflow, maven publishing, -SNAPSHOT as alpha ([d236bd3](https://github.com/kernel-panic-codecave/Boilerplate/commit/d236bd35d51b4a116869f46794d3e02859db31f3)) - KernelPanic

### Chores

- stop tracking IDE project files ([4f61336](https://github.com/kernel-panic-codecave/Boilerplate/commit/4f613361c69bd263f325d9382755555c48aa9272)) - KernelPanic
- **registry:** wire up registries, datagen, and network channel for everything above ([41f59f3](https://github.com/kernel-panic-codecave/Boilerplate/commit/41f59f3a519b23552b308ec3decb061856b26456)) - KernelPanic
- pick up Archie's newly-generic NestedNBTHolderMap, gametest server flag ([1df5f9e](https://github.com/kernel-panic-codecave/Boilerplate/commit/1df5f9ee4346959c13e1406e2f6cf194bc354f52)) - KernelPanic
- pick up Archie's newly-generic NestedNBTHolderMap in HookBlockEntity ([a7f9834](https://github.com/kernel-panic-codecave/Boilerplate/commit/a7f9834b8fa563063fe2bd8e4b34cc5f64f67148)) - KernelPanic
- bump dependencies, swap CLAUDE.md for AGENTS.md, drop unused placeholder textures ([9e196db](https://github.com/kernel-panic-codecave/Boilerplate/commit/9e196db2786be807ec90b927fe4ec97ce733bd0a)) - KernelPanic
- regenerate datagen output for the Multipart/Crafting CPU rewrite ([33b556a](https://github.com/kernel-panic-codecave/Boilerplate/commit/33b556acb05e071705e5ba846742bfb47345273e)) - KernelPanic
- wire remaining datagen registrations and regenerate output ([baf2de2](https://github.com/kernel-panic-codecave/Boilerplate/commit/baf2de22bf39618fe3a3c965f60e02b4e8c69431)) - KernelPanic
- rename the mod from Tubular Storage to Boilerplate **BREAKING** ([9ec777d](https://github.com/kernel-panic-codecave/Boilerplate/commit/9ec777dae6759b314cd41f2b56b71a35481dfd2b)) - KernelPanic
- point mod_url/mod_source at the renamed GitHub repo ([8c0fd29](https://github.com/kernel-panic-codecave/Boilerplate/commit/8c0fd29d0163d8e631c2622376d9252e125550c3)) - KernelPanic
- enable the JUnit gametest bridge by default ([a3f0f63](https://github.com/kernel-panic-codecave/Boilerplate/commit/a3f0f6344e3cdf4b8b50be4e20622f9f32675333)) - KernelPanic

### Style

- **assets:** regen rack, gantry, and pressure-tank block models ([b6d8874](https://github.com/kernel-panic-codecave/Boilerplate/commit/b6d8874f3ea3b8e0ba2b8013d0206e241ab46539)) - KernelPanic

### Other Changes

- Merge branch 'feat/pipe-hooks' ([7ba977d](https://github.com/kernel-panic-codecave/Boilerplate/commit/7ba977dbc4d3d4db44416795a539086131e33112)) - KernelPanic
- Create LICENSE ([c9edef8](https://github.com/kernel-panic-codecave/Boilerplate/commit/c9edef8d60df94f4da33414f8d9cab575087d801)) - KernelPanic
- Rename LICENSE to LICENSE.md ([4ccc5d7](https://github.com/kernel-panic-codecave/Boilerplate/commit/4ccc5d7b5dc75cb6de8ce3abb74231d23e923d63)) - KernelPanic
- Revert "feat(crafting): let the terminal grid pull ingredients on shift/ctrl-click" ([823bf90](https://github.com/kernel-panic-codecave/Boilerplate/commit/823bf90473c554b00e96c470540cf0fda94fa99b)) - KernelPanic
- Revert the outbound-buffer "fixes" in 5fda185 and 8898ef7 ([bfd1ed8](https://github.com/kernel-panic-codecave/Boilerplate/commit/bfd1ed8a4839d9360e160fc979e8b77cd2054038)) - KernelPanic

