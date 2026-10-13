package dev.nts.expdxr.mixin.options;

import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the option list that every options sub-screen inherits.
 *
 * <p>The field lives in the superclass - {@code OptionsSubScreen.java:30} is
 * {@code protected @Nullable OptionsList list} - while {@code VideoSettingsScreen}
 * only inherits it. A {@code @Shadow} field is resolved against the mixin's own
 * target class only, so shadowing {@code list} from {@code VideoSettingsScreen}
 * fails with {@code @Shadow field list was not located in the target class
 * net.minecraft.client.gui.screens.options.VideoSettingsScreen}. An accessor on
 * the declaring class resolves it.
 */
@Mixin(OptionsSubScreen.class)
public interface OptionsSubScreenAccessor {
    @Accessor("list")
    OptionsList dxr_mc$getOptionsList();
}