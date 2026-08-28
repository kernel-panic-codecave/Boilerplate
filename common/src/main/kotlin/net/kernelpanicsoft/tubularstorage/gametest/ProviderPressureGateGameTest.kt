package net.kernelpanicsoft.tubularstorage.gametest

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.tubularstorage.pipe.entity.FilterMode
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.RoutingModule
import net.kernelpanicsoft.tubularstorage.pipe.hook.ProviderHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.SyncHookType
import net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity

/**
 * GameTest coverage for [RequestFulfillment.fulfillFromProvider]'s own
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.HookHolderState.active] check - a
 * [ProviderHookType]/[SyncHookType] hook's passive stock exposure is gated by pressure the same way
 * [net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity.hasPressure] gates a
 * warehouse retrieve (see [WarehousePressureGateGameTest]) - previously a request against either
 * hook still extracted and dispatched regardless of whether the hook itself had any pressure to
 * operate on. Resolution/search visibility ([RequestFulfillment.reachableProviders]) stays ungated
 * either way, matching [RequestFulfillment.reachableWarehouses]'s own equivalent split - only an
 * actual withdrawal checks.
 */
@Suppress("unused")
class ProviderPressureGateGameTest {
	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testAProviderHookWithoutPressureIsSkippedByFulfillFromProvider() {
		val sourceChestPos = BlockPos(0, 2, 0)
		val providerPipePos = BlockPos(0, 2, 1)
		val destPos = BlockPos(0, 2, 2)
		setBlock(sourceChestPos, Blocks.CHEST.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())
		(getBlockEntity(sourceChestPos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))

		setBlock(providerPipePos, BlockRegistry.Multipart.defaultBlockState())
		val providerTile = getBlockEntity(providerPipePos) as MultipartBlockEntity
		providerTile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val providerState = providerTile.hooks.getOrPut(Direction.NORTH.name) { ProviderHookType.createState() } as SortingHookState
		providerState.routing = RoutingModule(mode = FilterMode.BLACKLIST)
		// Deliberately no pressure source anywhere near providerPipePos.

		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))
		runAfterDelay(10) {
			val serverLevel = level as ServerLevel
			val dispatched = RequestFulfillment.request(serverLevel, absolutePos(providerPipePos), ResourceStack(diamond, 4), absolutePos(destPos))
			assertTrue(dispatched == 0L) { "Expected nothing to be dispatched from a provider hook with no reachable pressure, got $dispatched" }
			succeed()
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testAProviderHookWithPressureIsUsedByFulfillFromProvider() {
		val sourceChestPos = BlockPos(0, 2, 0)
		val providerPipePos = BlockPos(0, 2, 1)
		val destPos = BlockPos(0, 2, 2)
		setBlock(sourceChestPos, Blocks.CHEST.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())
		(getBlockEntity(sourceChestPos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))

		setBlock(providerPipePos, BlockRegistry.Multipart.defaultBlockState())
		val providerTile = getBlockEntity(providerPipePos) as MultipartBlockEntity
		providerTile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val providerState = providerTile.hooks.getOrPut(Direction.NORTH.name) { ProviderHookType.createState() } as SortingHookState
		providerState.routing = RoutingModule(mode = FilterMode.BLACKLIST)
		placeCreativePressureSource(providerPipePos.above())

		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))
		runAfterDelay(10) {
			val serverLevel = level as ServerLevel
			val dispatched = RequestFulfillment.request(serverLevel, absolutePos(providerPipePos), ResourceStack(diamond, 4), absolutePos(destPos))
			assertTrue(dispatched == 4L) { "Expected the request to dispatch once the provider hook had reachable pressure, got $dispatched" }
			succeed()
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testASyncHookWithoutPressureIsSkippedByFulfillFromProvider() {
		val sourceChestPos = BlockPos(0, 2, 0)
		val syncPipePos = BlockPos(0, 2, 1)
		val destPos = BlockPos(0, 2, 2)
		setBlock(sourceChestPos, Blocks.CHEST.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())
		(getBlockEntity(sourceChestPos) as ChestBlockEntity).setItem(0, ItemStack(Items.GOLD_INGOT, 8))

		setBlock(syncPipePos, BlockRegistry.Multipart.defaultBlockState())
		val syncTile = getBlockEntity(syncPipePos) as MultipartBlockEntity
		syncTile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val syncState = syncTile.hooks.getOrPut(Direction.NORTH.name) { SyncHookType.createState() } as SortingHookState
		syncState.routing = RoutingModule(mode = FilterMode.BLACKLIST)
		// Deliberately no pressure source anywhere near syncPipePos.

		val gold = ItemResource.of(ItemStack(Items.GOLD_INGOT))
		runAfterDelay(10) {
			val serverLevel = level as ServerLevel
			val dispatched = RequestFulfillment.request(serverLevel, absolutePos(syncPipePos), ResourceStack(gold, 4), absolutePos(destPos))
			assertTrue(dispatched == 0L) { "Expected nothing to be dispatched from a sync hook with no reachable pressure, got $dispatched" }
			succeed()
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testASyncHookWithPressureIsUsedByFulfillFromProvider() {
		val sourceChestPos = BlockPos(0, 2, 0)
		val syncPipePos = BlockPos(0, 2, 1)
		val destPos = BlockPos(0, 2, 2)
		setBlock(sourceChestPos, Blocks.CHEST.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())
		(getBlockEntity(sourceChestPos) as ChestBlockEntity).setItem(0, ItemStack(Items.GOLD_INGOT, 8))

		setBlock(syncPipePos, BlockRegistry.Multipart.defaultBlockState())
		val syncTile = getBlockEntity(syncPipePos) as MultipartBlockEntity
		syncTile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val syncState = syncTile.hooks.getOrPut(Direction.NORTH.name) { SyncHookType.createState() } as SortingHookState
		syncState.routing = RoutingModule(mode = FilterMode.BLACKLIST)
		placeCreativePressureSource(syncPipePos.above())

		val gold = ItemResource.of(ItemStack(Items.GOLD_INGOT))
		runAfterDelay(10) {
			val serverLevel = level as ServerLevel
			val dispatched = RequestFulfillment.request(serverLevel, absolutePos(syncPipePos), ResourceStack(gold, 4), absolutePos(destPos))
			assertTrue(dispatched == 4L) { "Expected the request to dispatch once the sync hook had reachable pressure, got $dispatched" }
			succeed()
		}
	}
}
