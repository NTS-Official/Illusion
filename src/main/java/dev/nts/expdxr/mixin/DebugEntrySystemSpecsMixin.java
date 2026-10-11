package dev.nts.expdxr.mixin;

import dev.nts.expdxr.dx12.Dx12DebugInfo;
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
 * Appends DXRMC's own rows to the F3 "system specs" block.
 *
 * <p>Vanilla's block already carries the four GPU/display parameters - the
 * window resolution with the device vendor, the device name with its
 * integrated/discrete tag, and the backend with its driver string - and those are
 * now truthful for the D3D12 backend because {@code Dx12Device} builds its
 * {@code DeviceInfo} from the native adapter query. What vanilla has nowhere to put
 * is the resolution-plus-VRAM line and the mod's backend-identity line, so those are
 * added here, directly below, in the same group.
 *
 * <p>Nothing is added while the game renders through OpenGL or Vulkan:
 * {@code Dx12AdapterInfo.current} is null then, which is how the two backends' own
 * F3 output is left completely untouched.
 */
@Mixin(DebugEntrySystemSpecs.class)
public abstract class DebugEntrySystemSpecsMixin {
    @Inject(method = "display", at = @At("TAIL"))
    private void dxr_mc$appendDx12Lines(
            DebugScreenDisplayer displayer,
            Level serverOrClientLevel,
            LevelChunk clientChunk,
            LevelChunk serverChunk,
            CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            return;
        }
        List<String> lines = Dx12DebugInfo.extraSystemLines(
                minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
        if (!lines.isEmpty()) {
            displayer.addToGroup(Dx12DebugInfo.SYSTEM_GROUP, lines);
        }
    }
}