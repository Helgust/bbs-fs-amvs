package mchorse.bbs_mod.cubic.render.vao;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJLoader;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.joml.Matrices;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

public class BOBJModelVAO
{
    public BOBJLoader.CompiledData data;
    public BOBJArmature armature;

    private int vao;
    private int count;

    /* GL buffers */
    public int vertexBuffer;
    public int normalBuffer;
    public int lightBuffer;
    public int texCoordBuffer;
    public int tangentBuffer;

    private float[] tmpVertices;
    private float[] tmpNormals;
    private int[] tmpLight;
    private float[] tmpTangents;

    /**
     * Snapshot of the armature pose (+ the shader/stencil state) that the currently-uploaded buffers were
     * skinned from. If the next frame's pose and state are identical, the skinning + GPU upload is skipped
     * entirely — idle characters otherwise pay full per-vertex skinning every frame. Null until the first
     * upload. See {@link #poseUnchanged}.
     */
    private Matrix4f[] lastMatrices;
    private boolean lastHasShaders;
    private int lastStencilState = Integer.MIN_VALUE;

    public BOBJModelVAO(BOBJLoader.CompiledData data)
    {
        this.data = data;
        this.armature = this.data.mesh.armature;

        this.initBuffers();
    }

    /**
     * Initiate buffers. This method is responsible for allocating 
     * buffers for the data to be passed to VBOs and also generating the 
     * VBOs themselves. 
     */
    private void initBuffers()
    {
        this.vao = GL30.glGenVertexArrays();

        GL30.glBindVertexArray(this.vao);

        this.vertexBuffer = GL30.glGenBuffers();
        this.normalBuffer = GL30.glGenBuffers();
        this.lightBuffer = GL30.glGenBuffers();
        this.texCoordBuffer = GL30.glGenBuffers();
        this.tangentBuffer = GL30.glGenBuffers();

        this.count = this.data.normData.length / 3;
        this.tmpVertices = new float[this.data.posData.length];
        this.tmpNormals = new float[this.data.normData.length];
        this.tmpLight = new int[this.data.posData.length];
        this.tmpTangents = new float[this.count * 4];

        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, this.vertexBuffer);
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, this.data.posData, GL30.GL_DYNAMIC_DRAW);
        GL30.glVertexAttribPointer(Attributes.POSITION, 3, GL30.GL_FLOAT, false, 0, 0);

        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, this.normalBuffer);
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, this.data.normData, GL30.GL_DYNAMIC_DRAW);
        GL30.glVertexAttribPointer(Attributes.NORMAL, 3, GL30.GL_FLOAT, false, 0, 0);

        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, this.lightBuffer);
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, this.tmpLight, GL30.GL_DYNAMIC_DRAW);
        GL30.glVertexAttribIPointer(Attributes.LIGHTMAP_UV, 2, GL30.GL_INT, 0, 0);

        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, this.texCoordBuffer);
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, this.data.texData, GL30.GL_STATIC_DRAW);
        GL30.glVertexAttribPointer(Attributes.TEXTURE_UV, 2, GL30.GL_FLOAT, false, 0, 0);

        /* Tangents are re-uploaded every frame under Iris, so allocate DYNAMIC here and refresh with
         * glBufferSubData in updateMesh rather than reallocating the store each frame with glBufferData. */
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, this.tangentBuffer);
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, this.tmpTangents, GL30.GL_DYNAMIC_DRAW);
        GL30.glVertexAttribPointer(Attributes.TANGENTS, 4, GL30.GL_FLOAT, false, 0, 0);

        /* Mid-texcoords deliberately share texCoordBuffer (they never diverge from the plain
         * texcoords here); just re-point the attribute at the already-uploaded buffer instead of
         * allocating a second dead buffer and uploading texData twice. */
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, this.texCoordBuffer);
        GL30.glVertexAttribPointer(Attributes.MID_TEXTURE_UV, 2, GL30.GL_FLOAT, false, 0, 0);

        /* Leave no VAO bound: GL calls after construction must not accidentally mutate this VAO's state. */
        GL30.glBindVertexArray(0);
    }

    /**
     * Clean up resources which were used by this  
     */
    public void delete()
    {
        GL30.glDeleteVertexArrays(this.vao);

        GL15.glDeleteBuffers(this.vertexBuffer);
        GL15.glDeleteBuffers(this.normalBuffer);
        GL15.glDeleteBuffers(this.lightBuffer);
        GL15.glDeleteBuffers(this.texCoordBuffer);
        GL15.glDeleteBuffers(this.tangentBuffer);
    }

    /**
     * Update this mesh. This method is responsible for applying 
     * matrix transformations to vertices and normals according to its 
     * bone owners and these bone influences.
     */
    public void updateMesh(StencilMap stencilMap)
    {
        boolean hasShaders = BBSRendering.isIrisShadersEnabled();
        int stencilState = stencilMap == null ? -1 : (stencilMap.increment ? 1 : 0);
        Matrix4f[] matrices = this.armature.matrices;

        /* Nothing that affects the skinned output changed since the last upload (same bone matrices, same
         * Iris/tangent state, same stencil light state): the buffers already hold the correct data, so skip
         * the per-vertex skinning and the GPU upload entirely. Big win for posed-but-idle characters. */
        if (this.poseUnchanged(matrices, hasShaders, stencilState))
        {
            return;
        }

        Vector4f sum = new Vector4f();
        Vector4f result = new Vector4f(0F, 0F, 0F, 0F);
        Vector3f sumNormal = new Vector3f();
        Vector3f resultNormal = new Vector3f();

        float[] oldVertices = this.data.posData;
        float[] newVertices = this.tmpVertices;
        float[] oldNormals = this.data.normData;
        float[] newNormals = this.tmpNormals;

        for (int i = 0, c = this.count; i < c; i++)
        {
            int count = 0;
            float maxWeight = -1;
            int lightBone = -1;

            for (int w = 0; w < 4; w++)
            {
                float weight = this.data.weightData[i * 4 + w];

                if (weight > 0)
                {
                    int index = this.data.boneIndexData[i * 4 + w];

                    sum.set(oldVertices[i * 3], oldVertices[i * 3 + 1], oldVertices[i * 3 + 2], 1F);
                    matrices[index].transform(sum);
                    result.add(sum.mul(weight));

                    sumNormal.set(oldNormals[i * 3], oldNormals[i * 3 + 1], oldNormals[i * 3 + 2]);
                    Matrices.TEMP_3F.set(matrices[index]).transform(sumNormal);
                    resultNormal.add(sumNormal.mul(weight));

                    count++;

                    if (weight > maxWeight)
                    {
                        lightBone = index;
                        maxWeight = weight;
                    }
                }
            }

            if (count == 0)
            {
                result.set(oldVertices[i * 3], oldVertices[i * 3 + 1], oldVertices[i * 3 + 2], 1F);
                resultNormal.set(oldNormals[i * 3], oldNormals[i * 3 + 1], oldNormals[i * 3 + 2]);
            }

            result.x /= result.w;
            result.y /= result.w;
            result.z /= result.w;

            newVertices[i * 3] = result.x;
            newVertices[i * 3 + 1] = result.y;
            newVertices[i * 3 + 2] = result.z;

            newNormals[i * 3] = resultNormal.x;
            newNormals[i * 3 + 1] = resultNormal.y;
            newNormals[i * 3 + 2] = resultNormal.z;

            result.set(0F, 0F, 0F, 0F);
            resultNormal.set(0F, 0F, 0F);

            if (stencilMap != null)
            {
                this.tmpLight[i * 2] = Math.max(0, stencilMap.increment ? lightBone : 0);
                this.tmpLight[i * 2 + 1] = 0;
            }
        }

        this.processData(newVertices, newNormals);

        /* glBufferSubData refreshes the existing (correctly-sized, DYNAMIC) store in place instead of
         * glBufferData reallocating a fresh store every frame. */
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vertexBuffer);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, newVertices);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.normalBuffer);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, newNormals);

        if (hasShaders)
        {
            BBSRendering.calculateTangents(this.tmpTangents, newVertices, newNormals, this.data.texData);

            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.tangentBuffer);
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, this.tmpTangents);
        }

        if (stencilMap != null)
        {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.lightBuffer);
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, this.tmpLight);
        }

        this.snapshotPose(matrices, hasShaders, stencilState);
    }

    /**
     * True when the current pose + shader/stencil state exactly matches what the uploaded buffers were last
     * skinned from, so {@link #updateMesh} can be skipped. Comparing bone matrices is O(bones) — far cheaper
     * than re-skinning O(vertices). Bone transforms are recomputed with identical float ops for an identical
     * pose, so exact equality holds for a genuinely idle character.
     */
    private boolean poseUnchanged(Matrix4f[] matrices, boolean hasShaders, int stencilState)
    {
        if (this.lastMatrices == null || this.lastHasShaders != hasShaders || this.lastStencilState != stencilState)
        {
            return false;
        }

        if (this.lastMatrices.length != matrices.length)
        {
            return false;
        }

        for (int i = 0; i < matrices.length; i++)
        {
            if (!matrices[i].equals(this.lastMatrices[i]))
            {
                return false;
            }
        }

        return true;
    }

    private void snapshotPose(Matrix4f[] matrices, boolean hasShaders, int stencilState)
    {
        if (this.lastMatrices == null || this.lastMatrices.length != matrices.length)
        {
            this.lastMatrices = new Matrix4f[matrices.length];

            for (int i = 0; i < matrices.length; i++)
            {
                this.lastMatrices[i] = new Matrix4f(matrices[i]);
            }
        }
        else
        {
            for (int i = 0; i < matrices.length; i++)
            {
                this.lastMatrices[i].set(matrices[i]);
            }
        }

        this.lastHasShaders = hasShaders;
        this.lastStencilState = stencilState;
    }

    protected void processData(float[] newVertices, float[] newNormals)
    {}

    /*
     * CAUTION (2026-07-16): the render-perf changes here + in updateMesh were NOT yet visually verified on
     * an animated BOBJ character. Regressions to check when one is available:
     *  - animation plays smoothly (the updateMesh skip-when-idle must NOT freeze a moving character, and an
     *    idle character must not go stale — toggle a pose and confirm it updates on the next frame);
     *  - textures/lighting/fog correct; normal-mapped lighting right under Iris (tangents via glBufferSubData);
     *  - stencil picking still highlights the right bone (light buffer path).
     * The changes: setupUniforms split into setupFrameUniforms + setupDrawUniforms (see ModelVAORenderer);
     * per-draw glGetInteger VAO/element-buffer save+restore replaced with VertexBuffer.unbind(); updateMesh
     * now skips skinning+upload when pose/shader/stencil state is unchanged and uses glBufferSubData.
     */
    public void render(ShaderProgram shader, MatrixStack stack, float r, float g, float b, float a, StencilMap stencilMap, int light, int overlay)
    {
        boolean hasShaders = BBSRendering.isIrisShadersEnabled();

        GL30.glVertexAttrib4f(Attributes.COLOR, r, g, b, a);
        GL30.glVertexAttribI2i(Attributes.OVERLAY_UV, overlay & '\uffff', overlay >> 16 & '\uffff');
        GL30.glVertexAttribI2i(Attributes.LIGHTMAP_UV, light & '\uffff', light >> 16 & '\uffff');

        ModelVAORenderer.setupFrameUniforms(shader);
        ModelVAORenderer.setupDrawUniforms(stack, shader);

        shader.bind();

        GL30.glBindVertexArray(this.vao);

        GL30.glEnableVertexAttribArray(Attributes.POSITION);
        GL30.glEnableVertexAttribArray(Attributes.TEXTURE_UV);
        GL30.glEnableVertexAttribArray(Attributes.NORMAL);

        if (stencilMap != null) GL30.glEnableVertexAttribArray(Attributes.LIGHTMAP_UV);
        if (hasShaders) GL30.glEnableVertexAttribArray(Attributes.TANGENTS);
        if (hasShaders) GL30.glEnableVertexAttribArray(Attributes.MID_TEXTURE_UV);

        GL30.glDrawArrays(GL30.GL_TRIANGLES, 0, this.count);

        GL30.glDisableVertexAttribArray(Attributes.POSITION);
        GL30.glDisableVertexAttribArray(Attributes.TEXTURE_UV);
        GL30.glDisableVertexAttribArray(Attributes.NORMAL);

        if (stencilMap != null) GL30.glDisableVertexAttribArray(Attributes.LIGHTMAP_UV);
        if (hasShaders) GL30.glDisableVertexAttribArray(Attributes.TANGENTS);
        if (hasShaders) GL30.glDisableVertexAttribArray(Attributes.MID_TEXTURE_UV);

        shader.unbind();

        /* Was: glGetInteger(GL_VERTEX_ARRAY_BINDING/GL_ELEMENT_ARRAY_BUFFER_BINDING) save + restore, a
         * GPU pipeline sync per draw. Unbind instead — VertexBuffer.unbind() binds VAO 0 and clears
         * vanilla's currentVertexBuffer cache; the element-array binding is VAO state, so it travels with
         * the now-unbound VAO and needs no separate restore. */
        VertexBuffer.unbind();
    }
}