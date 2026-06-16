package mchorse.bbs_mod.film;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.camera.clips.CameraClip;
import mchorse.bbs_mod.camera.clips.CameraClipContext;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.joml.Matrices;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a spatial trajectory line (with optional look-direction cues) for a
 * trajectory-driven camera clip, similar to how {@link Recorder} renders the
 * camera frustum preview.
 *
 * The trajectory line is drawn as a camera-facing ribbon (a flat strip that
 * keeps its face turned toward the viewer), which gives a smooth, constant-width
 * line without the twisting/faceting you'd get from extruding a square tube.
 *
 * @see CameraClip#isTrajectory()
 */
public class CameraTrajectoryRenderer
{
    /** Lower/upper bounds on how many samples are taken along a clip's duration. */
    private static final int MIN_SAMPLES = 48;
    private static final int MAX_SAMPLES = 512;
    /** Roughly how many look-direction cues to spread along the trajectory. */
    private static final int CUE_COUNT = 14;

    /**
     * Half-width of the ribbon per block of distance from the camera (keeps the
     * line a roughly constant thickness on screen), with sane world-space bounds.
     */
    private static final float LINE_SCREEN_SCALE = 0.006F;
    private static final float LINE_MIN_HALF = 0.02F;
    private static final float LINE_MAX_HALF = 0.4F;

    /** Cues use a slightly thicker screen-scaled width than the line. */
    private static final float CUE_SCREEN_SCALE = 0.009F;
    private static final float CUE_MIN_HALF = 0.03F;
    private static final float CUE_MAX_HALF = 0.5F;
    private static final float CUE_LENGTH = 0.5F;

    public static void renderTrajectory(Clips clips, Clip selected, Camera camera, MatrixStack stack)
    {
        if (!BBSSettings.editorCameraTrajectory.get() || clips == null)
        {
            return;
        }

        if (!(selected instanceof CameraClip cameraClip) || !cameraClip.isTrajectory())
        {
            return;
        }

        List<Position> samples = sample(cameraClip);

        if (samples.size() < 2)
        {
            return;
        }

        ClipFactoryData data = clips.getFactory().getData(selected);
        int color = data == null ? 0xffffff : data.color;
        float r = ((color >> 16) & 0xFF) / 255F;
        float g = ((color >> 8) & 0xFF) / 255F;
        float b = (color & 0xFF) / 255F;

        double cx = camera.getPos().x;
        double cy = camera.getPos().y;
        double cz = camera.getPos().z;

        boolean orientation = BBSSettings.editorCameraTrajectoryOrientation.get();

        BufferBuilder builder = Tessellator.getInstance().getBuffer();

        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.disableCull();
        builder.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);

        /* Position-only trajectory line, as a smooth camera-facing ribbon */
        renderRibbon(builder, stack, samples, cx, cy, cz, r, g, b);

        /* Look-direction cues, anchored on the line in a contrasting color */
        if (orientation)
        {
            Matrix4f matrix = stack.peek().getPositionMatrix();
            int cueEvery = Math.max(1, (samples.size() - 1) / CUE_COUNT);

            /* Complementary-ish tone so cues read as separate from the line */
            float cr = 1F - r;
            float cg = 1F - g;
            float cb = 1F - b;

            for (int i = 0; i < samples.size(); i += cueEvery)
            {
                Position p = samples.get(i);
                Vector3f forward = Matrices.rotation(MathUtils.toRad(p.angle.pitch), MathUtils.toRad(180 - p.angle.yaw));

                float ax = (float) (p.point.x - cx);
                float ay = (float) (p.point.y - cy);
                float az = (float) (p.point.z - cz);
                float bx = ax + forward.x * CUE_LENGTH;
                float by = ay + forward.y * CUE_LENGTH;
                float bz = az + forward.z * CUE_LENGTH;

                float dist = (float) Math.sqrt(ax * ax + ay * ay + az * az);
                float half = MathUtils.clamp(dist * CUE_SCREEN_SCALE, CUE_MIN_HALF, CUE_MAX_HALF);

                drawRibbonSegment(builder, matrix, ax, ay, az, bx, by, bz, half, cr, cg, cb);
            }
        }

        BufferRenderer.drawWithGlobalProgram(builder.end());

        RenderSystem.enableCull();
        RenderSystem.disableDepthTest();
    }

    /**
     * Builds a camera-facing ribbon through the sampled points. For each point
     * the ribbon is offset sideways along {@code tangent x view}, so the strip
     * always faces the camera and keeps a roughly constant width on screen.
     */
    private static void renderRibbon(BufferBuilder builder, MatrixStack stack, List<Position> samples, double cx, double cy, double cz, float r, float g, float b)
    {
        int n = samples.size();

        if (n < 2)
        {
            return;
        }

        Matrix4f matrix = stack.peek().getPositionMatrix();

        Vector3f[] rel = new Vector3f[n];

        for (int i = 0; i < n; i++)
        {
            Position p = samples.get(i);

            rel[i] = new Vector3f((float) (p.point.x - cx), (float) (p.point.y - cy), (float) (p.point.z - cz));
        }

        Vector3f tangent = new Vector3f();
        Vector3f view = new Vector3f();
        Vector3f side = new Vector3f();
        Vector3f prevSide = new Vector3f(0F, 1F, 0F);
        Vector3f prevLeft = new Vector3f();
        Vector3f prevRight = new Vector3f();

        for (int i = 0; i < n; i++)
        {
            if (i == 0) rel[1].sub(rel[0], tangent);
            else if (i == n - 1) rel[i].sub(rel[i - 1], tangent);
            else rel[i + 1].sub(rel[i - 1], tangent);

            if (tangent.lengthSquared() < 1.0E-8F) tangent.set(0F, 0F, 1F);
            tangent.normalize();

            /* View direction from the camera (origin in relative space) to the point */
            view.set(rel[i]);
            float dist = view.length();

            if (dist < 1.0E-4F)
            {
                view.set(0F, 0F, 1F);
                dist = 1F;
            }
            else
            {
                view.div(dist);
            }

            float half = MathUtils.clamp(dist * LINE_SCREEN_SCALE, LINE_MIN_HALF, LINE_MAX_HALF);

            tangent.cross(view, side);

            if (side.lengthSquared() < 1.0E-8F)
            {
                tangent.cross(0F, 1F, 0F, side);

                if (side.lengthSquared() < 1.0E-8F) side.set(1F, 0F, 0F);
            }

            side.normalize();

            /* Prevent the ribbon from flipping when the curve turns toward/away the camera */
            if (i > 0 && side.dot(prevSide) < 0F) side.negate();
            prevSide.set(side);

            float sx = side.x * half;
            float sy = side.y * half;
            float sz = side.z * half;
            Vector3f left = new Vector3f(rel[i].x - sx, rel[i].y - sy, rel[i].z - sz);
            Vector3f right = new Vector3f(rel[i].x + sx, rel[i].y + sy, rel[i].z + sz);

            if (i > 0)
            {
                builder.vertex(matrix, prevLeft.x, prevLeft.y, prevLeft.z).color(r, g, b, 1F).next();
                builder.vertex(matrix, prevRight.x, prevRight.y, prevRight.z).color(r, g, b, 1F).next();
                builder.vertex(matrix, right.x, right.y, right.z).color(r, g, b, 1F).next();

                builder.vertex(matrix, prevLeft.x, prevLeft.y, prevLeft.z).color(r, g, b, 1F).next();
                builder.vertex(matrix, right.x, right.y, right.z).color(r, g, b, 1F).next();
                builder.vertex(matrix, left.x, left.y, left.z).color(r, g, b, 1F).next();
            }

            prevLeft.set(left);
            prevRight.set(right);
        }
    }

    /**
     * Draws a single straight camera-facing ribbon segment from A to B (used for
     * the look-direction cues), so they stay clean from any viewing angle instead
     * of twisting like an extruded box.
     */
    private static void drawRibbonSegment(BufferBuilder builder, Matrix4f matrix, float ax, float ay, float az, float bx, float by, float bz, float half, float r, float g, float b)
    {
        float tx = bx - ax;
        float ty = by - ay;
        float tz = bz - az;
        float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);

        if (tl < 1.0E-6F)
        {
            return;
        }

        tx /= tl;
        ty /= tl;
        tz /= tl;

        /* View direction from the camera (origin) to the segment's midpoint */
        float vx = (ax + bx) * 0.5F;
        float vy = (ay + by) * 0.5F;
        float vz = (az + bz) * 0.5F;
        float vl = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);

        if (vl < 1.0E-6F)
        {
            vx = 0F;
            vy = 0F;
            vz = 1F;
            vl = 1F;
        }

        vx /= vl;
        vy /= vl;
        vz /= vl;

        /* side = tangent x view */
        float sx = ty * vz - tz * vy;
        float sy = tz * vx - tx * vz;
        float sz = tx * vy - ty * vx;
        float sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);

        if (sl < 1.0E-6F)
        {
            /* Segment points straight at the camera; fall back to a world-up offset */
            sx = ty * 0F - tz * 1F;
            sy = tz * 0F - tx * 0F;
            sz = tx * 1F - ty * 0F;
            sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);

            if (sl < 1.0E-6F)
            {
                sx = 1F;
                sy = 0F;
                sz = 0F;
                sl = 1F;
            }
        }

        sx = sx / sl * half;
        sy = sy / sl * half;
        sz = sz / sl * half;

        builder.vertex(matrix, ax - sx, ay - sy, az - sz).color(r, g, b, 1F).next();
        builder.vertex(matrix, ax + sx, ay + sy, az + sz).color(r, g, b, 1F).next();
        builder.vertex(matrix, bx + sx, by + sy, bz + sz).color(r, g, b, 1F).next();

        builder.vertex(matrix, ax - sx, ay - sy, az - sz).color(r, g, b, 1F).next();
        builder.vertex(matrix, bx + sx, by + sy, bz + sz).color(r, g, b, 1F).next();
        builder.vertex(matrix, bx - sx, by - sy, bz - sz).color(r, g, b, 1F).next();
    }

    private static List<Position> sample(CameraClip clip)
    {
        int duration = clip.duration.get();
        List<Position> samples = new ArrayList<>();

        if (duration <= 0)
        {
            return samples;
        }

        int steps = MathUtils.clamp(duration, MIN_SAMPLES, MAX_SAMPLES);
        int tick = clip.tick.get();
        int layer = clip.layer.get();
        CameraClipContext context = new CameraClipContext();

        for (int i = 0; i <= steps; i++)
        {
            float t = i / (float) steps * duration;
            int relativeTick = (int) Math.floor(t);
            float transition = t - relativeTick;

            context.setup(tick + relativeTick, relativeTick, transition, layer);

            Position position = new Position();

            clip.sampleTrajectory(context, position);
            samples.add(position);
        }

        return samples;
    }
}
