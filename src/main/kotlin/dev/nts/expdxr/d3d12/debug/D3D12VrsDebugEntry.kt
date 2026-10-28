package dev.nts.expdxr.d3d12.debug

import dev.nts.expdxr.Illusion
import dev.nts.expdxr.d3d12.render.advanced.vrs.D3D12VariableShadingRate
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer
import net.minecraft.client.gui.components.debug.DebugScreenEntry
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus
import net.minecraft.client.gui.components.debug.DebugScreenProfile
import net.minecraft.resources.Identifier
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.LevelChunk
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.RegisterDebugEntriesEvent

/**
 * F3 的 VRS 诊断行，注册成一等的 debug entry（`dxr_mc:vrs`）：玩家在 F3+F6 的
 * DebugOptionsScreen 里自己开关，选择会存进 `run/debug-profile.json`。
 * 注册必须走 [RegisterDebugEntriesEvent]：`DebugScreenEntries.register` 是 private。
 * 不覆写 `isAllowed`：沿用接口默认，reduced debug info 会把这一行一起藏掉。
 * 见 docs/misc/debug-and-crash.md §6。
 */
object D3D12VrsDebugEntry : DebugScreenEntry {
    /** `dxr_mc:vrs`；debug-profile.json 里就用这个名字存状态。 */
    val ID: Identifier = Identifier.fromNamespaceAndPath(Illusion.MODID, "vrs")

    override fun display(
        displayer: DebugScreenDisplayer,
        serverOrClientLevel: Level?,
        clientChunk: LevelChunk?,
        serverChunk: LevelChunk?,
    ) {
        displayer.addLine(D3D12VariableShadingRate.debugLine())
    }

    /**
     * 注册 + 默认归入 DEFAULT profile 的 `IN_OVERLAY`（按 F3 可见）。老玩家的
     * `debug-profile.json` 里没有这个 id，一开始是 NEVER —— 去 F3+F6 勾上即可。
     */
    fun register(modEventBus: IEventBus) {
        modEventBus.addListener<RegisterDebugEntriesEvent> { event ->
            event.register(ID, this)
            event.includeInProfile(ID, DebugScreenProfile.DEFAULT, DebugScreenEntryStatus.IN_OVERLAY)
            Illusion.LOGGER.info("[{}] debug entry {} registered (toggle it in F3+F6)", Illusion.MODID, ID)
        }
    }
}
