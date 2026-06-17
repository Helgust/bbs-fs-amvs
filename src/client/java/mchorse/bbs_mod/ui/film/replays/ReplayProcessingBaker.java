package mchorse.bbs_mod.ui.film.replays;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayProcessing;
import mchorse.bbs_mod.film.replays.ReplayProcessingOp;
import mchorse.bbs_mod.settings.values.IValueListener;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Client-side capture and re-bake of a replay's {@link ReplayProcessing} stack.
 * Lives in the client source set because it reuses {@link ReplayBatchProcessor};
 * the data model itself ({@link ReplayProcessing}) stays in the main source set.
 *
 * Re-bake resets the live replay data to the base snapshot and re-applies each
 * enabled operation in order. Advanced-expression and look-at operations are
 * re-evaluated here (not stored as static snapshots) so they stay composable.
 */
public class ReplayProcessingBaker
{
    /**
     * Reset the replay to its base and re-apply the enabled stack operations in
     * order. Does not notify; callers wrap this in {@code BaseValue.edit} so it
     * forms a single undo entry.
     */
    public static void rebake(Film film, Replay replay)
    {
        ReplayProcessing processing = replay.processing;

        processing.restoreBase(replay);

        for (ReplayProcessingOp op : processing.getOps())
        {
            if (op.enabled.get())
            {
                apply(film, replay, op);
            }
        }
    }

    private static void apply(Film film, Replay replay, ReplayProcessingOp op)
    {
        if (op.isType(ReplayProcessingOp.TYPE_DELTA))
        {
            for (Map.Entry<String, Double> entry : op.deltas.get().entrySet())
            {
                ReplayBatchProcessor.applyDelta(replay, entry.getKey(), entry.getValue());
            }

            float tickShift = op.tickShift.get();

            if (tickShift != 0F)
            {
                /* Time offset shifts keyframes, form properties and actions together. */
                replay.shift(tickShift);
            }
        }
        else if (op.isType(ReplayProcessingOp.TYPE_EXPRESSION))
        {
            List<ReplayBatchProcessor.VisibleReplay> single = List.of(new ReplayBatchProcessor.VisibleReplay(replay, op.exprI.get(), op.exprO.get()));

            ReplayBatchProcessor.applyAdvanced(single, new ArrayList<>(op.channels.get()), op.expression.get());
        }
        else if (op.isType(ReplayProcessingOp.TYPE_LOOKAT))
        {
            Replay target = resolveTarget(film, op.targetIndex.get());

            if (target == null || target == replay)
            {
                return;
            }

            ReplayBatchProcessor.NormalParams params = new ReplayBatchProcessor.NormalParams();
            params.lookAtTarget = target;

            List<ReplayBatchProcessor.VisibleReplay> single = List.of(new ReplayBatchProcessor.VisibleReplay(replay, 0, 0));

            ReplayBatchProcessor.applyNormal(single, Collections.emptyList(), ReplayBatchProcessor.Operation.LOOK_AT, params);
        }
    }

    private static Replay resolveTarget(Film film, int index)
    {
        if (film == null || index < 0)
        {
            return null;
        }

        List<Replay> replays = film.replays.getList();

        return index < replays.size() ? replays.get(index) : null;
    }

    /* Capture */

    /**
     * Records a processing/offset operation into the affected replays' stacks
     * non-destructively. The operation is run once (so deltas can be diffed and
     * validation can fail cleanly), then the replays are restored to their
     * pre-op state and the recipe is re-applied under a single undo entry per
     * replay. This way every op type gets a correct, uniform undo regardless of
     * whether the underlying processor notifies internally.
     *
     * @return whether the operation succeeded (the recipe was recorded).
     */
    public static boolean capture(Film film, List<ReplayBatchProcessor.VisibleReplay> selected, Collection<String> sampleChannels, BooleanSupplier op, EntryFactory factory)
    {
        List<PreState> pres = new ArrayList<>();

        for (ReplayBatchProcessor.VisibleReplay vr : selected)
        {
            pres.add(snapshot(vr.replay, sampleChannels));
        }

        if (!op.getAsBoolean())
        {
            for (int i = 0; i < selected.size(); i++)
            {
                restore(selected.get(i).replay, pres.get(i));
            }

            return false;
        }

        List<ReplayProcessingOp> ops = new ArrayList<>();

        for (int i = 0; i < selected.size(); i++)
        {
            ops.add(factory.create(selected.get(i), pres.get(i)));
        }

        for (int i = 0; i < selected.size(); i++)
        {
            restore(selected.get(i).replay, pres.get(i));
        }

        /* One shared group id ties together the per-replay ops created by this
         * single action, so stack edits can target them across the selection. */
        String group = UUID.randomUUID().toString();

        for (int i = 0; i < selected.size(); i++)
        {
            ReplayProcessingOp entry = ops.get(i);

            if (entry == null)
            {
                continue;
            }

            entry.group.set(group);

            Replay replay = selected.get(i).replay;

            BaseValue.edit(replay, IValueListener.FLAG_UNMERGEABLE, (r) ->
            {
                r.processing.captureBaseIfEmpty(r);
                r.processing.add(entry);

                rebake(film, r);
            });
        }

        return true;
    }

    private static PreState snapshot(Replay replay, Collection<String> sampleChannels)
    {
        return new PreState(replay.keyframes.toData(), replay.properties.toData(), replay.actions.toData(), sampleFirstValues(replay, sampleChannels));
    }

    private static void restore(Replay replay, PreState pre)
    {
        replay.keyframes.fromData(pre.keyframes);
        replay.properties.fromData(pre.properties);
        replay.actions.fromData(pre.actions);
    }

    /**
     * First-keyframe value (factory Y) of each given channel. Additive process
     * ops apply a uniform per-channel delta, so the first keyframe is enough to
     * recover that delta by diffing before/after.
     */
    public static Map<String, Double> sampleFirstValues(Replay replay, Collection<String> ids)
    {
        Map<String, Double> out = new LinkedHashMap<>();

        for (String id : ids)
        {
            BaseValue value = replay.keyframes.get(id);

            if (value instanceof KeyframeChannel channel && !channel.isEmpty())
            {
                Keyframe kf = channel.get(0);

                out.put(id, kf.getFactory().getY(kf.getValue()));
            }
        }

        return out;
    }

    public static Map<String, Double> diff(Map<String, Double> before, Map<String, Double> after)
    {
        Map<String, Double> deltas = new LinkedHashMap<>();

        for (Map.Entry<String, Double> entry : after.entrySet())
        {
            Double prev = before.get(entry.getKey());

            if (prev != null)
            {
                double delta = entry.getValue() - prev;

                if (delta != 0D)
                {
                    deltas.put(entry.getKey(), delta);
                }
            }
        }

        return deltas;
    }

    public interface EntryFactory
    {
        ReplayProcessingOp create(ReplayBatchProcessor.VisibleReplay vr, PreState pre);
    }

    public static class PreState
    {
        public final BaseType keyframes;
        public final BaseType properties;
        public final BaseType actions;
        public final Map<String, Double> before;

        public PreState(BaseType keyframes, BaseType properties, BaseType actions, Map<String, Double> before)
        {
            this.keyframes = keyframes;
            this.properties = properties;
            this.actions = actions;
            this.before = before;
        }
    }
}
