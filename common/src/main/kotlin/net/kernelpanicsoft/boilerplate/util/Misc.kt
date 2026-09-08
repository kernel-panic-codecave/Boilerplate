package net.kernelpanicsoft.boilerplate.util

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.entity.BlockEntity

inline fun <reified T : BlockEntity> LevelAccessor.blockEntity(pos: BlockPos) = getBlockEntity(pos) as? T?

inline val Player.level: LevelAccessor get() = level()