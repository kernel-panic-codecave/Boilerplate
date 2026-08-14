package net.kernelpanicsoft.tubularstorage.pipe.hook

/** Self-contained state for one [WarehouseTerminalHookType] attachment - none needed beyond the [type] tag every [HookHolderState] already carries; search results live in the menu/network, not persisted here. */
class WarehouseTerminalHookState : HookHolderState(WarehouseTerminalHookType.ID)
