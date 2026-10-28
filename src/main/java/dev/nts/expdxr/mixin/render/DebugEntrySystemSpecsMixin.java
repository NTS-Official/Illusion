package dev.nts.expdxr.mixin.render;

import dev.nts.expdxr.d3d12.debug.D3D12DebugInfo;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntrySystemSpecs;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 往 F3 的 "system specs" 块追加本模组的行（分辨率 + VRAM、后端标识）。
 *
 * <p>非 D3D12 时 `D3D12AdapterInfo.current` 为 null，返回空列表，其它后端自己的 F3 输出保持原样。
 * 见 docs/misc/mixins-and-earlywindow.md §3.8
 */
@Mixin(DebugEntrySystemSpecs.class)
public abstract class DebugEntrySystemSpecsMixin {
    @Inject(method = "display", at = @At("TAIL"))
    private void dxr_mc$appendD3D12Lines(DebugScreenDisplayer displayer,
            Level serverOrClientLevel, LevelChunk clientChunk, LevelChunk serverChunk,
            CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        List<String> lines = D3D12DebugInfo.extraSystemLines(
                minecraft.getWindow().getWidth(),
                minecraft.getWindow().getHeight()
        );
        if (!lines.isEmpty()) displayer.addToGroup(D3D12DebugInfo.SYSTEM_GROUP, lines);
    }
}