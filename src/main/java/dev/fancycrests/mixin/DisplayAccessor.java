package dev.fancycrests.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the private static {@link EntityDataAccessor}s of the abstract
 * {@link Display} base class so the renderer can build
 * {@link net.minecraft.network.syncher.SynchedEntityData.DataValue}s for
 * virtual text_display entities that never exist server-side as real entities.
 *
 * <p>Read-only static accessors; no access widener is used.</p>
 */
@Mixin(Display.class)
public interface DisplayAccessor {

    @Accessor("DATA_TRANSLATION_ID")
    static EntityDataAccessor<Vector3fc> fancycrestsTranslationId() {
        throw new AssertionError();
    }

    @Accessor("DATA_BILLBOARD_RENDER_CONSTRAINTS_ID")
    static EntityDataAccessor<Byte> fancycrestsBillboardId() {
        throw new AssertionError();
    }

    @Accessor("DATA_VIEW_RANGE_ID")
    static EntityDataAccessor<Float> fancycrestsViewRangeId() {
        throw new AssertionError();
    }
}
