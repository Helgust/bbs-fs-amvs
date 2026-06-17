package mchorse.bbs_mod.film.replays;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.utils.manager.BaseManager;
import mchorse.bbs_mod.utils.manager.storage.JSONLikeStorage;

import java.io.File;
import java.util.function.Supplier;

/**
 * World-independent library of standalone {@link Replay} assets.
 *
 * Replays are stored as human-readable JSON (form + keyframes + processing +
 * properties + actions all ride {@link Replay#toData()}), so they can be
 * shared and transferred between films and worlds. Unlike films (which are
 * compressed and per-world), this manager points at a global config folder.
 */
public class ReplayManager extends BaseManager<Replay>
{
    public ReplayManager(Supplier<File> folder)
    {
        super(folder);

        this.backUps = true;
        this.storage = new JSONLikeStorage().json();
    }

    @Override
    protected Replay createData(String id, MapType mapType)
    {
        Replay replay = new Replay(id);

        if (mapType != null)
        {
            replay.fromData(mapType);
        }

        return replay;
    }
}
