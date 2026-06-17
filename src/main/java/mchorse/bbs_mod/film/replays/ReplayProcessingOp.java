package mchorse.bbs_mod.film.replays;

import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.settings.values.ui.ValueStringKeys;

import java.util.Collection;
import java.util.Map;

/**
 * A single entry in a replay's {@link ReplayProcessing} stack. It records the
 * resolved, per-replay effect of a processing or time offset operation so that
 * the stack can be re-baked on top of the base keyframes (see
 * {@link ReplayProcessing}).
 *
 * Three kinds of entries are supported:
 *
 * - {@link #TYPE_DELTA}: additive per-channel value deltas and/or a tick shift.
 *   Covers line/shift/random/shape/fit-height processing and time offset.
 * - {@link #TYPE_EXPRESSION}: an advanced math expression re-evaluated per
 *   keyframe at re-bake time, using the replay's captured {@code i}/{@code o}.
 * - {@link #TYPE_LOOKAT}: re-evaluated to point at a target replay (by index).
 */
public class ReplayProcessingOp extends ValueGroup
{
    public static final String TYPE_DELTA = "delta";
    public static final String TYPE_EXPRESSION = "expression";
    public static final String TYPE_LOOKAT = "lookat";

    public final ValueBoolean enabled = new ValueBoolean("enabled", true);
    public final ValueString type = new ValueString("type", TYPE_DELTA);
    public final ValueString label = new ValueString("label", "");

    /**
     * Shared id for all per-replay ops created together by one process/offset
     * action across a multi-selection. Lets stack edits (delete/toggle) target
     * "the same operation" in every selected replay's separate stack. Empty for
     * legacy/imported ops, which then fall back to focused-replay-only edits.
     */
    public final ValueString group = new ValueString("group", "");

    /* TYPE_DELTA */
    public final ValueFloat tickShift = new ValueFloat("tick_shift", 0F);
    public final ValueDoubleMap deltas = new ValueDoubleMap("deltas");

    /* TYPE_EXPRESSION */
    public final ValueString expression = new ValueString("expression", "");
    public final ValueInt exprI = new ValueInt("i", 0);
    public final ValueInt exprO = new ValueInt("o", 0);
    public final ValueStringKeys channels = new ValueStringKeys("channels");

    /* TYPE_LOOKAT */
    public final ValueInt targetIndex = new ValueInt("target", -1);

    public ReplayProcessingOp()
    {
        this("");
    }

    public ReplayProcessingOp(String id)
    {
        super(id);

        this.add(this.enabled);
        this.add(this.type);
        this.add(this.label);
        this.add(this.group);

        this.add(this.tickShift);
        this.add(this.deltas);

        this.add(this.expression);
        this.add(this.exprI);
        this.add(this.exprO);
        this.add(this.channels);

        this.add(this.targetIndex);
    }

    public static ReplayProcessingOp delta(String label, Map<String, Double> channelDeltas, float tickShift)
    {
        ReplayProcessingOp op = new ReplayProcessingOp();

        op.type.set(TYPE_DELTA);
        op.label.set(label);
        op.tickShift.set(tickShift);
        op.deltas.get().putAll(channelDeltas);

        return op;
    }

    public static ReplayProcessingOp expression(String label, String expression, int i, int o, Collection<String> channels)
    {
        ReplayProcessingOp op = new ReplayProcessingOp();

        op.type.set(TYPE_EXPRESSION);
        op.label.set(label);
        op.expression.set(expression);
        op.exprI.set(i);
        op.exprO.set(o);
        op.channels.get().addAll(channels);

        return op;
    }

    public static ReplayProcessingOp lookAt(String label, int targetIndex)
    {
        ReplayProcessingOp op = new ReplayProcessingOp();

        op.type.set(TYPE_LOOKAT);
        op.label.set(label);
        op.targetIndex.set(targetIndex);

        return op;
    }

    public boolean isType(String type)
    {
        return this.type.get().equals(type);
    }
}
