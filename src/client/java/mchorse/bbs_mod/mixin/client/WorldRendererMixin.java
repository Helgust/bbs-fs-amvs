package mchorse.bbs_mod.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.utils.colors.Color;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public class WorldRendererMixin
{
    @Shadow
    public Framebuffer entityOutlinesFramebuffer;

    @Inject(method = "renderSky(Lnet/minecraft/client/util/math/MatrixStack;Lorg/joml/Matrix4f;FLnet/minecraft/client/render/Camera;ZLjava/lang/Runnable;)V", at = @At("HEAD"), cancellable = true)
    public void onRenderSky(CallbackInfo info)
    {
        if (BBSSettings.chromaSkyEnabled.get())
        {
            Integer fromCurve = BBSRendering.getChromaSkyColorArgb();
            int argb = fromCurve != null ? fromCurve : BBSSettings.chromaSkyColor.get();
            Color color = Color.rgba(argb);

            GL11.glClearColor(color.r, color.g, color.b, 1F);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            RenderSystem.setShaderFogColor(color.r, color.g, color.b, 1F);

            info.cancel();
        }
    }

    @Inject(method = "renderLayer", at = @At("HEAD"), cancellable = true)
    public void onRenderLayer(RenderLayer renderLayer, MatrixStack matrices, double cameraX, double cameraY, double cameraZ, Matrix4f positionMatrix, CallbackInfo info)
    {
        if (BBSSettings.chromaSkyEnabled.get() && !BBSSettings.chromaSkyTerrain.get())
        {
            /* renderLayer is called 5+ times per frame (solid, cutout, cutout-mipped, translucent,
             * tripwire). Only render the BBS films once — on the solid layer, matching the TAIL hook —
             * otherwise, under Iris, onRenderChunkLayer would draw every film once per layer call
             * (~5-10x/frame). Every layer is still cancelled to suppress the chroma-hidden terrain. */
            if (renderLayer == RenderLayer.getSolid())
            {
                BBSRendering.onRenderChunkLayer(matrices);
            }

            info.cancel();
        }
    }

    @Inject(method = "renderLayer", at = @At("TAIL"))
    public void onRenderChunkLayer(RenderLayer layer, MatrixStack stack, double x, double y, double z, Matrix4f positionMatrix, CallbackInfo info)
    {
        if (layer == RenderLayer.getSolid())
        {
            BBSRendering.onRenderChunkLayer(stack);
        }
    }

    @Inject(at = @At("RETURN"), method = "loadEntityOutlinePostProcessor")
    private void onLoadEntityOutlineShader(CallbackInfo info)
    {
        /* This fires as part of a WorldRenderer.reload() — i.e. during the resource/shader-enable reload
         * storm, when vanilla (and Iris) are already reallocating everything. When BBS's size-lie is off,
         * vanilla has just recreated these framebuffers at the correct window size, so re-resizing them
         * here is redundant work piled onto that stall. Only intervene when custom size is active, where
         * the reload may have left them at window size while BBS needs them at video size. */
        if (!BBSRendering.isCustomSize())
        {
            return;
        }

        BBSRendering.resizeExtraFramebuffers();
    }

    @Inject(at = @At("RETURN"), method = "onResized")
    private void onResized(CallbackInfo info)
    {
        if (this.entityOutlinesFramebuffer == null)
        {
            return;
        }

        /* Fires on every real window resize AND focus change. Vanilla already resizes its own extra
         * framebuffers on a genuine resize; BBS only needs to intervene when its size-lie may have left
         * them at video size. Skipping this when custom size is off removes the alt-tab / focus-change
         * hitch (each call touches 6 framebuffers and can cascade into an Iris pipeline rebuild). */
        if (!BBSRendering.isCustomSize())
        {
            /* Lie inactive here, so getFramebufferWidth()/getWidth() report the real display: refresh the
             * DPI scale so a monitor/scaling change (e.g. dragging to a 150% display) is picked up. */
            BBSModClient.updateOriginalFramebufferScale();

            return;
        }

        BBSRendering.resizeExtraFramebuffers();
    }
}