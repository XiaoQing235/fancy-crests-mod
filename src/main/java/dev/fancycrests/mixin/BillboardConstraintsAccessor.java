package dev.fancycrests.mixin;

import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the private {@code getId()} of {@link Display.BillboardConstraints},
 * which encodes the wire byte stored in the
 * {@code DATA_BILLBOARD_RENDER_CONSTRAINTS_ID} metadata entry. Verified for
 * 26.2: FIXED=0, VERTICAL=1, HORIZONTAL=2, CENTER=3; the invoker keeps us
 * independent of a hardcoded constant.
 */
@Mixin(Display.BillboardConstraints.class)
public interface BillboardConstraintsAccessor {

    @Invoker("getId")
    byte fancycrestsGetId();
}
