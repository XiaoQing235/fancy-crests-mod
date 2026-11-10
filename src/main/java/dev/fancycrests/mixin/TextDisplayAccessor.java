package dev.fancycrests.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the private static {@link EntityDataAccessor}s of
 * {@link Display.TextDisplay} (the 26.2 class name; there is no
 * {@code TextDisplayEntity}) for building metadata packets of virtual
 * text_display label entities.
 *
 * <p>Read-only static accessors; no access widener is used.</p>
 */
@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccessor {

    @Accessor("DATA_TEXT_ID")
    static EntityDataAccessor<net.minecraft.network.chat.Component> fancycrestsTextId() {
        throw new AssertionError();
    }

    @Accessor("DATA_BACKGROUND_COLOR_ID")
    static EntityDataAccessor<Integer> fancycrestsBackgroundColorId() {
        throw new AssertionError();
    }

    @Accessor("DATA_TEXT_OPACITY_ID")
    static EntityDataAccessor<Byte> fancycrestsTextOpacityId() {
        throw new AssertionError();
    }

    @Accessor("DATA_STYLE_FLAGS_ID")
    static EntityDataAccessor<Byte> fancycrestsStyleFlagsId() {
        throw new AssertionError();
    }
}
