package net.kernelpanicsoft.tubularstorage.pipe.entity

import kotlinx.serialization.Serializable

enum class FilterMode { WHITELIST, BLACKLIST }

/** Sorting configuration applied to a [PipeBlockEntity] once a sorting module item is used on it. */
@Serializable
data class RoutingModule(
	val mode: FilterMode = FilterMode.WHITELIST,
	val priority: Int = 0,
	val color: SDyeColor? = null,
)
