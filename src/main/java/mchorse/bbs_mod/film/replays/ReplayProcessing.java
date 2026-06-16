package mchorse.bbs_mod.film.replays;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.settings.values.base.BaseValueGroup;
import mchorse.bbs_mod.settings.values.core.ValueGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Non-destructive processing stack for a single {@link Replay}.
 *
 * It stores a {@link #base} snapshot of the replay's animatable data plus an
 * ordered list of {@link ReplayProcessingOp} entries (the "recipe"). The live
 * replay data is produced by re-baking: reset to {@link #base}, then apply each
 * enabled op in order. Re-baking itself lives client-side (see
 * {@code ReplayProcessingBaker}) because it reuses {@code ReplayBatchProcessor};
 * this class only holds and serializes the data plus the data-only operations
 * (capture base, add/remove/move, clear, flatten).
 *
 * The base snapshots keyframes, form properties and action clips because a time
 * offset ({@link Replay#shift(float)}) shifts all three; processing ops only
 * touch keyframes. While the stack {@link #isActive() is active} the data is
 * owned by the re-bake (Lock + Flatten model): {@link #flatten()} keeps the
 * current result and drops the stack, {@link #clear(Replay)} restores the base.
 */
public class ReplayProcessing extends ValueGroup
{
    private static final String BASE_KEYFRAMES = "keyframes";
    private static final String BASE_PROPERTIES = "properties";
    private static final String BASE_ACTIONS = "actions";

    private MapType base = new MapType();

    private final List<ReplayProcessingOp> ops = new ArrayList<>();

    public ReplayProcessing(String id)
    {
        super(id);
    }

    public boolean isActive()
    {
        return !this.ops.isEmpty();
    }

    public List<ReplayProcessingOp> getOps()
    {
        return Collections.unmodifiableList(this.ops);
    }

    public int size()
    {
        return this.ops.size();
    }

    /**
     * Snapshot the replay's animatable data as the base, but only when the stack
     * is empty (i.e. right before adding the very first operation). Once a base
     * exists it is preserved until {@link #clear(Replay)} or {@link #flatten()}.
     */
    public void captureBaseIfEmpty(Replay replay)
    {
        if (this.ops.isEmpty())
        {
            MapType data = new MapType();

            data.put(BASE_KEYFRAMES, replay.keyframes.toData());
            data.put(BASE_PROPERTIES, replay.properties.toData());
            data.put(BASE_ACTIONS, replay.actions.toData());

            this.base = data;
        }
    }

    /**
     * Reset the replay's animatable data to the captured base. Used as the first
     * step of a re-bake and when clearing the stack.
     */
    public void restoreBase(Replay replay)
    {
        if (this.base.has(BASE_KEYFRAMES))
        {
            replay.keyframes.fromData(this.base.getMap(BASE_KEYFRAMES));
        }

        if (this.base.has(BASE_PROPERTIES))
        {
            replay.properties.fromData(this.base.getMap(BASE_PROPERTIES));
        }

        if (this.base.has(BASE_ACTIONS))
        {
            replay.actions.fromData(this.base.getList(BASE_ACTIONS));
        }
    }

    public void add(ReplayProcessingOp op)
    {
        if (op != null)
        {
            this.ops.add(op);
        }
    }

    public void remove(ReplayProcessingOp op)
    {
        this.ops.remove(op);
    }

    public void move(int from, int to)
    {
        if (from < 0 || from >= this.ops.size() || to < 0 || to >= this.ops.size() || from == to)
        {
            return;
        }

        this.ops.add(to, this.ops.remove(from));
    }

    /**
     * Restore the base into the replay and drop the whole recipe.
     */
    public void clear(Replay replay)
    {
        this.restoreBase(replay);
        this.ops.clear();
    }

    /**
     * Drop the recipe but keep the live data as it is (the baked result becomes
     * the new free-editing state).
     */
    public void flatten()
    {
        this.ops.clear();
    }

    /* Value implementation */

    @Override
    public void copy(BaseValueGroup group)
    {
        if (group instanceof ReplayProcessing other)
        {
            this.fromData(other.toData());
        }
    }

    @Override
    public BaseType toData()
    {
        MapType data = new MapType();

        if (!this.isActive())
        {
            return data;
        }

        data.put("base", this.base);

        ListType list = new ListType();

        for (ReplayProcessingOp op : this.ops)
        {
            list.add(op.toData());
        }

        data.put("ops", list);

        return data;
    }

    @Override
    public void fromData(BaseType data)
    {
        this.ops.clear();
        this.base = new MapType();

        if (!data.isMap())
        {
            return;
        }

        MapType map = data.asMap();

        if (map.has("base"))
        {
            this.base = map.getMap("base");
        }

        if (map.has("ops"))
        {
            for (BaseType type : map.getList("ops"))
            {
                if (!type.isMap())
                {
                    continue;
                }

                ReplayProcessingOp op = new ReplayProcessingOp();

                op.fromData(type);
                this.ops.add(op);
            }
        }
    }
}
