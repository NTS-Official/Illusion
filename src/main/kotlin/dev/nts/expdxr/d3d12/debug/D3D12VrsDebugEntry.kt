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
 * F3 里的 VRS 诊断行，做成一等的原版 debug entry（P237 收尾）。
 *
 * 之前这一行是塞在 `system_specs` 组里的（[D3D12DebugInfo.extraSystemLines]），
 * 后果是只要 F3 开着就一定在，玩家既关不掉也没法只留它 —— 而它恰恰是一条
 * 调试信息。26.3 把 F3 的每一块都改成了 `DebugScreenEntry`，`F3 + F6` 打开的
 * `DebugOptionsScreen` 就是逐个 entry 的开关，并且会把选择存进
 * `run/debug-profile.json`。所以这里注册一个独立 entry：玩家自己在那个 GUI
 * 里决定要不要看这一行。
 *
 * 注册必须走 NeoForge 的 [RegisterDebugEntriesEvent]（mod 事件总线）——
 * `DebugScreenEntries.register` 是 private，官方的模组入口就是
 * `DebugScreenEntryList` 构造时 post 的这个事件。
 *
 * `view` 语义：本地图没有 `isAllowed` 覆写，沿用接口默认（`!reducedDebugInfo`），
 * 因此"隐藏调试信息"（F3 的 reduced debug info）依然会连这行一起藏掉，符合直觉。
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
     * 注册 + 默认归入 DEFAULT profile 的 `IN_OVERLAY` 档（= 按 F3 时可见）。
     *
     * 老玩家如果已经有一份自定义的 `debug-profile.json`，里面当然没有这个新 id，
     * 于是它一开始是 `NEVER`（关）—— 这正是"能被玩家选择"的意思，去 F3+F6 里
     * 勾上即可；新装/默认 profile 则直接可见，与它作为诊断行的用途一致。
     */
    fun register(modEventBus: IEventBus) {
        modEventBus.addListener<RegisterDebugEntriesEvent> { event ->
            event.register(ID, this)
            event.includeInProfile(ID, DebugScreenProfile.DEFAULT, DebugScreenEntryStatus.IN_OVERLAY)
            Illusion.LOGGER.info("[{}] debug entry {} registered (toggle it in F3+F6)", Illusion.MODID, ID)
        }
    }
}
