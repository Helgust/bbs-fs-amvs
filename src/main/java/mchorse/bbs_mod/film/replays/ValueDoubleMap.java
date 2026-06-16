package mchorse.bbs_mod.film.replays;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.settings.values.base.BaseValueBasic;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A simple string-to-double map value. Used by {@link ReplayProcessingOp} to
 * store per-channel keyframe deltas of a processing operation.
 */
public class ValueDoubleMap extends BaseValueBasic<Map<String, Double>>
{
    public ValueDoubleMap(String id)
    {
        super(id, new LinkedHashMap<>());
    }

    @Override
    public BaseType toData()
    {
        MapType data = new MapType();

        for (Map.Entry<String, Double> entry : this.value.entrySet())
        {
            data.putDouble(entry.getKey(), entry.getValue());
        }

        return data;
    }

    @Override
    public void fromData(BaseType data)
    {
        this.value.clear();

        if (!data.isMap())
        {
            return;
        }

        MapType map = data.asMap();

        for (String key : map.keys())
        {
            this.value.put(key, map.getDouble(key));
        }
    }
}
