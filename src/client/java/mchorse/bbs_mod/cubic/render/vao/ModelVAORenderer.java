package mchorse.bbs_mod.cubic.render.vao;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;

public class ModelVAORenderer
{
    /** Sampler uniform names cached so the per-draw loop doesn't concatenate 12 strings every draw. */
    private static final String[] SAMPLER_NAMES = new String[12];

    /** Reused across draws (render thread is single-threaded) so the per-draw modelView multiply doesn't allocate. */
    private static final Matrix4f MODEL_VIEW = new Matrix4f();

    static
    {
        for (int i = 0; i < SAMPLER_NAMES.length; i++)
        {
            SAMPLER_NAMES[i] = "Sampler" + i;
        }
    }

    /**
     * Standalone single draw: sets both the frame-constant and per-draw uniforms. Used by callers that
     * render one VAO in isolation ({@link mchorse.bbs_mod.forms.renderers.ExtrudedFormRenderer}). For a
     * multi-group model walk prefer calling {@link #setupFrameUniforms(ShaderProgram)} once and
     * {@link #renderDraw} per group so the frame-constant work isn't repeated.
     */
    public static void render(ShaderProgram shader, IModelVAO modelVAO, MatrixStack stack, float r, float g, float b, float a, int light, int overlay)
    {
        setupFrameUniforms(shader);
        renderDraw(shader, modelVAO, stack, r, g, b, a, light, overlay);
    }

    /**
     * A single draw assuming the frame-constant uniforms were already uploaded for this shader this frame.
     * Sets only the per-draw uniforms (samplers + modelView + normal), binds, draws and unbinds.
     */
    public static void renderDraw(ShaderProgram shader, IModelVAO modelVAO, MatrixStack stack, float r, float g, float b, float a, int light, int overlay)
    {
        setupDrawUniforms(stack, shader);

        shader.bind();
        modelVAO.render(shader.getFormat(), r, g, b, a, light, overlay);
        shader.unbind();

        /* Was: glGetInteger(GL_VERTEX_ARRAY_BINDING/GL_ELEMENT_ARRAY_BUFFER_BINDING) save + restore, which
         * forces a GPU pipeline sync per draw. Vanilla/Sodium rebind their VAO before every draw and never
         * rely on a stale binding, so simply unbinding is safe. VertexBuffer.unbind() binds VAO 0 *and*
         * clears vanilla's currentVertexBuffer cache so it can't go stale. */
        VertexBuffer.unbind();
    }

    /**
     * Uniforms that are constant across every group/material of a single model render (one render pass):
     * projection, view rotation, fog, color modulator, game time, texture matrix and lights. Upload these
     * once per model walk rather than per draw.
     */
    public static void setupFrameUniforms(ShaderProgram shader)
    {
        if (shader.projectionMat != null)
        {
            shader.projectionMat.set(RenderSystem.getProjectionMatrix());
        }

        if (shader.viewRotationMat != null)
        {
            shader.viewRotationMat.set(RenderSystem.getInverseViewRotationMatrix());
        }

        if (shader.fogStart != null)
        {
            shader.fogStart.set(RenderSystem.getShaderFogStart());
        }

        if (shader.fogEnd != null)
        {
            shader.fogEnd.set(RenderSystem.getShaderFogEnd());
        }

        if (shader.fogColor != null)
        {
            shader.fogColor.set(RenderSystem.getShaderFogColor());
        }

        if (shader.fogShape != null)
        {
            shader.fogShape.set(RenderSystem.getShaderFogShape().getId());
        }

        if (shader.colorModulator != null)
        {
            shader.colorModulator.set(1F, 1F, 1F, 1F);
        }

        if (shader.gameTime != null)
        {
            shader.gameTime.set(RenderSystem.getShaderGameTime());
        }

        if (shader.textureMat != null)
        {
            shader.textureMat.set(RenderSystem.getTextureMatrix());
        }

        RenderSystem.setupShaderLights(shader);
    }

    /**
     * Per-draw uniforms: the samplers (the model texture at Sampler0 changes per material, so these must be
     * re-bound each draw) and the group's modelView + normal matrices.
     */
    public static void setupDrawUniforms(MatrixStack stack, ShaderProgram shader)
    {
        for (int i = 0; i < SAMPLER_NAMES.length; i++)
        {
            shader.addSampler(SAMPLER_NAMES[i], RenderSystem.getShaderTexture(i));
        }

        if (shader.modelViewMat != null)
        {
            shader.modelViewMat.set(MODEL_VIEW.set(RenderSystem.getModelViewMatrix()).mul(stack.peek().getPositionMatrix()));
        }

        /* NormalMat is present by default in Iris' shaders, but when there is no Iris,
         * the BBS mod's model.json shader is being used instead that provides NormalMat
         * uniform.
         */
        GlUniform normalUniform = shader.getUniform("NormalMat");

        if (normalUniform != null)
        {
            normalUniform.set(stack.peek().getNormalMatrix());
        }
    }
}
