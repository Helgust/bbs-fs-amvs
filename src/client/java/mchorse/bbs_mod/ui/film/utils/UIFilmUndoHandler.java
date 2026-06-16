package mchorse.bbs_mod.ui.film.utils;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.network.ClientNetwork;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.forms.editors.UIFormUndoHandler;
import mchorse.bbs_mod.utils.Timer;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.undo.UndoManager;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class UIFilmUndoHandler extends UIFormUndoHandler
{
    /** Default editor domain (camera editor is shown first). */
    public static final String DOMAIN_CAMERA = "camera";
    public static final String DOMAIN_REPLAY = "replay";
    public static final String DOMAIN_ACTIONS = "actions";

    private Timer actionsTimer = new Timer(100);
    private Set<BaseValue> syncData = new HashSet<>();

    /**
     * One undo manager per editor (camera/replay/actions). The inherited
     * {@link #undoManager} field always points at the active editor's manager,
     * so all the base class machinery (submit/undo/redo/timers) operates on it
     * without further changes.
     */
    private Map<String, UndoManager<ValueGroup>> domainManagers;
    private String activeDomain = DOMAIN_CAMERA;

    public UIFilmUndoHandler(UIFilmPanel panel)
    {
        super(panel);
    }

    @Override
    public void reset()
    {
        /* Invoked from the base constructor before this class' field initializers
         * run, so the map must be created here rather than via a field initializer. */
        this.domainManagers = new LinkedHashMap<>();
        this.domainManagers.put(DOMAIN_CAMERA, this.createUndoManager());
        this.domainManagers.put(DOMAIN_REPLAY, this.createUndoManager());
        this.domainManagers.put(DOMAIN_ACTIONS, this.createUndoManager());

        this.activeDomain = DOMAIN_CAMERA;
        this.undoManager = this.domainManagers.get(DOMAIN_CAMERA);
    }

    public String getActiveDomain()
    {
        return this.activeDomain;
    }

    /**
     * Point undo/redo at the given editor's history. Any pending edits are flushed
     * into the currently active manager first, so changes never land in the wrong
     * history when switching editors.
     */
    public void setActiveDomain(String domain)
    {
        if (this.domainManagers == null)
        {
            return;
        }

        UndoManager<ValueGroup> manager = this.domainManagers.get(domain);

        if (manager == null || manager == this.undoManager)
        {
            return;
        }

        this.submitUndo();

        this.activeDomain = domain;
        this.undoManager = manager;
    }

    @Override
    public void handlePreValues(BaseValue baseValue, int flag)
    {
        /* time_spent_active is a passive counter updated every second; it should not
         * pollute undo history with dozens of entries per minute */
        if (baseValue.getPath().getLast().equals("time_spent_active"))
        {
            return;
        }

        super.handlePreValues(baseValue, flag);
    }

    @Override
    protected void handleValue(BaseValue value)
    {
        super.handleValue(value);

        if (this.isReplayActions(value))
        {
            /* TODO: Variant A for the lazy-channel desync — if 'value' is a keyframe
             * inside a channel, promote it to its parent KeyframeChannel here so the
             * sync sends the whole channel ('properties/<key>') instead of an indexed
             * keyframe path ('properties/<key>/0'). A channel created client-side by
             * FormProperties.getOrCreate during UI building (UIReplaysEditor
             * .collectFormPropertySheets) is never synced, so the server lacks it and
             * the indexed path can't be resolved. Currently handled reactively by the
             * full-film resync request (ServerNetwork.requestFilmResync). */
            this.syncData.add(value);
            this.actionsTimer.mark();
        }
    }

    @Override
    protected void handleTimers()
    {
        super.handleTimers();

        if (this.actionsTimer.checkReset())
        {
            for (BaseValue syncData : this.syncData)
            {
                ClientNetwork.sendSyncData(((UIFilmPanel) this.uiElement).getData().getId(), syncData);
            }

            this.syncData.clear();
        }
    }

    private boolean isReplayActions(BaseValue value)
    {
        String path = value.getPath().toString();

        if (
            path.endsWith("/replays") ||
            path.endsWith("/keyframes") ||
            path.contains("/keyframes/x") ||
            path.contains("/keyframes/y") ||
            path.contains("/keyframes/z") ||
            path.contains("/keyframes/item_main_hand") ||
            path.contains("/keyframes/item_off_hand") ||
            path.contains("/keyframes/item_head") ||
            path.contains("/keyframes/item_chest") ||
            path.contains("/keyframes/item_legs") ||
            path.contains("/keyframes/item_feet") ||
            path.contains("/properties/") ||
            path.endsWith("/properties") ||
            path.endsWith("/actor") ||
            path.endsWith("/enabled") ||
            path.endsWith("/form")
        ) {
            return true;
        }

        /* Specifically for overwriting full replay like what's done when recording
         * data in the world! */
        if (value.getParent() != null && value.getParent().getId().equals("replays"))
        {
            return true;
        }

        while (value != null)
        {
            if (value instanceof Clips clips && clips.getFactory() == BBSMod.getFactoryActionClips())
            {
                return true;
            }

            value = value.getParent();
        }

        return false;
    }
}