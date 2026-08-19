package net.kernelpanicsoft.tubularstorage.pipe.hook

/** Self-contained state for one [TerminalHookType] attachment - none needed beyond the [type] tag every [HookHolderState] already carries; search results live in the menu/network, not persisted here. */
class TerminalHookState : HookHolderState(TerminalHookType.ID)
