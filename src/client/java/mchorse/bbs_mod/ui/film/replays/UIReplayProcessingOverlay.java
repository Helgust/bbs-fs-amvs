package mchorse.bbs_mod.ui.film.replays;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayProcessingOp;
import mchorse.bbs_mod.settings.values.IValueListener;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified processing window for the selected replays. The two top buttons add
 * operations to the whole selection: "Add time offset" opens a formula prompt
 * (shifting keyframes, properties and actions) and "Add processing" opens the
 * coordinate processing panel. Both record onto each replay's non-destructive
 * {@link mchorse.bbs_mod.film.replays.ReplayProcessing} stack.
 *
 * Below them the stack list shows the focused replay's recorded operations with
 * drag-reorder, enable toggle and delete. Bake keeps the current result and
 * drops the recipe; Clear restores the base. Bake and Clear apply to every
 * selected replay. Every edit re-bakes so the result updates live.
 */
public class UIReplayProcessingOverlay extends UIOverlayPanel
{
    private final UIReplayList replays;
    private final UIFilmPanel panel;
    private final Replay replay;

    private final OpList list;

    public UIReplayProcessingOverlay(UIReplayList replays, Replay replay)
    {
        super(UIKeys.SCENE_REPLAYS_PROCESSING_TITLE.format(replay.getName()));

        this.replays = replays;
        this.panel = replays.panel;
        this.replay = replay;

        UILabel addHeader = UI.label(UIKeys.SCENE_REPLAYS_PROCESSING_ADD_HEADER);
        UILabel stackHeader = UI.label(UIKeys.SCENE_REPLAYS_PROCESSING_STACK_HEADER);

        UIButton offset = new UIButton(UIKeys.SCENE_REPLAYS_PROCESSING_APPLY_OFFSET, (b) -> this.addOffset());
        offset.tooltip(UIKeys.SCENE_REPLAYS_PROCESSING_APPLY_OFFSET_TOOLTIP);

        UIButton process = new UIButton(UIKeys.SCENE_REPLAYS_PROCESSING_ADD, (b) -> this.addProcessing());
        process.tooltip(UIKeys.SCENE_REPLAYS_PROCESSING_ADD_TOOLTIP);

        UIElement addRow = UI.row(offset, process);

        this.list = new OpList();
        this.list.sorting().background();
        this.list.scroll.scrollItemSize = 20;
        this.list.context((menu) ->
        {
            ReplayProcessingOp op = this.list.getCurrentFirst();

            if (op != null)
            {
                menu.action(op.enabled.get() ? Icons.INVISIBLE : Icons.VISIBLE, UIKeys.SCENE_REPLAYS_PROCESSING_TOGGLE, () -> this.toggle(op));
                menu.action(Icons.REMOVE, UIKeys.SCENE_REPLAYS_PROCESSING_DELETE, () -> this.delete(op));
            }
        });
        this.list.keys().register(Keys.DELETE, () ->
        {
            ReplayProcessingOp op = this.list.getCurrentFirst();

            if (op != null)
            {
                this.delete(op);
            }
        }).inside();

        UIButton bake = new UIButton(UIKeys.SCENE_REPLAYS_PROCESSING_BAKE, (b) -> this.bake());
        bake.tooltip(UIKeys.SCENE_REPLAYS_PROCESSING_BAKE_TOOLTIP);

        UIButton clear = new UIButton(UIKeys.SCENE_REPLAYS_PROCESSING_CLEAR, (b) -> this.clear());
        clear.tooltip(UIKeys.SCENE_REPLAYS_PROCESSING_CLEAR_TOOLTIP);

        UIElement buttons = UI.row(clear, bake);

        addHeader.relative(this.content).xy(6, 6).w(1F, -12).h(16);
        addRow.relative(this.content).x(6).y(24).w(1F, -12).h(20);
        stackHeader.relative(this.content).xy(6, 50).w(1F, -12).h(16);
        this.list.relative(this.content).x(6).y(68).w(1F, -12).h(1F, -94);
        buttons.relative(this.content).x(6).y(1F, -26).w(1F, -12).h(20);

        this.content.add(addHeader, addRow, stackHeader, this.list, buttons);

        this.refresh();
    }

    private void addOffset()
    {
        this.replays.openOffsetPrompt(this::afterChange);
    }

    private void addProcessing()
    {
        this.replays.openProcessPanel(this::afterChange);
    }

    private void toggle(ReplayProcessingOp op)
    {
        Film film = this.panel.getData();
        String group = op.group.get();
        boolean value = !op.enabled.get();

        if (group == null || group.isEmpty())
        {
            BaseValue.edit(this.replay, IValueListener.FLAG_UNMERGEABLE, (r) ->
            {
                op.enabled.set(value);
                ReplayProcessingBaker.rebake(film, r);
            });
        }
        else
        {
            for (Replay r : this.selectedReplays())
            {
                List<ReplayProcessingOp> matches = matchingOps(r, group);

                if (matches.isEmpty())
                {
                    continue;
                }

                BaseValue.edit(r, IValueListener.FLAG_UNMERGEABLE, (rr) ->
                {
                    for (ReplayProcessingOp m : matches)
                    {
                        m.enabled.set(value);
                    }

                    ReplayProcessingBaker.rebake(film, rr);
                });
            }
        }

        this.afterChange();
    }

    private void delete(ReplayProcessingOp op)
    {
        Film film = this.panel.getData();
        String group = op.group.get();

        if (group == null || group.isEmpty())
        {
            BaseValue.edit(this.replay, IValueListener.FLAG_UNMERGEABLE, (r) ->
            {
                r.processing.remove(op);
                ReplayProcessingBaker.rebake(film, r);
            });
        }
        else
        {
            for (Replay r : this.selectedReplays())
            {
                List<ReplayProcessingOp> matches = matchingOps(r, group);

                if (matches.isEmpty())
                {
                    continue;
                }

                BaseValue.edit(r, IValueListener.FLAG_UNMERGEABLE, (rr) ->
                {
                    for (ReplayProcessingOp m : matches)
                    {
                        rr.processing.remove(m);
                    }

                    ReplayProcessingBaker.rebake(film, rr);
                });
            }
        }

        this.afterChange();
    }

    /**
     * The ops in {@code replay}'s stack that belong to the given group (the ops
     * created together with the focused op across the multi-selection).
     */
    private static List<ReplayProcessingOp> matchingOps(Replay replay, String group)
    {
        List<ReplayProcessingOp> out = new ArrayList<>();

        for (ReplayProcessingOp op : replay.processing.getOps())
        {
            if (group.equals(op.group.get()))
            {
                out.add(op);
            }
        }

        return out;
    }

    /**
     * Keep the baked result and drop the recipe, for every selected replay.
     */
    private void bake()
    {
        for (Replay r : this.selectedReplays())
        {
            BaseValue.edit(r, IValueListener.FLAG_UNMERGEABLE, (rr) -> rr.processing.flatten());
        }

        this.afterChange();
    }

    /**
     * Restore the base and drop the recipe, for every selected replay.
     */
    private void clear()
    {
        for (Replay r : this.selectedReplays())
        {
            BaseValue.edit(r, IValueListener.FLAG_UNMERGEABLE, (rr) -> rr.processing.clear(rr));
        }

        this.afterChange();
    }

    private List<Replay> selectedReplays()
    {
        List<Replay> selected = this.replays.getSelectedReplays();

        return selected.isEmpty() ? List.of(this.replay) : selected;
    }

    private void afterChange()
    {
        this.refresh();
        this.panel.getController().createEntities();
        this.panel.replayEditor.updateChannelsList();
    }

    private void refresh()
    {
        this.list.setList(new ArrayList<>(this.replay.processing.getOps()));
    }

    private static Icon iconFor(ReplayProcessingOp op)
    {
        if (op.isType(ReplayProcessingOp.TYPE_EXPRESSION))
        {
            return Icons.CODE;
        }

        if (op.isType(ReplayProcessingOp.TYPE_LOOKAT))
        {
            return Icons.LOOKING;
        }

        if (op.tickShift.get() != 0F && op.deltas.get().isEmpty())
        {
            return Icons.TIME;
        }

        return Icons.ALL_DIRECTIONS;
    }

    private class OpList extends UIList<ReplayProcessingOp>
    {
        public OpList()
        {
            super(null);
        }

        @Override
        protected void handleSwap(int from, int to)
        {
            Film film = UIReplayProcessingOverlay.this.panel.getData();

            BaseValue.edit(UIReplayProcessingOverlay.this.replay, IValueListener.FLAG_UNMERGEABLE, (r) ->
            {
                r.processing.move(from, to);
                ReplayProcessingBaker.rebake(film, r);
            });

            UIReplayProcessingOverlay.this.afterChange();
            this.setIndex(to);
        }

        @Override
        protected String elementToString(UIContext context, int i, ReplayProcessingOp element)
        {
            String label = element.label.get();

            return label == null || label.isEmpty() ? element.type.get() : label;
        }

        @Override
        protected void renderElementPart(UIContext context, ReplayProcessingOp element, int i, int x, int y, boolean hover, boolean selected)
        {
            int h = this.scroll.scrollItemSize;
            boolean enabled = element.enabled.get();

            context.batcher.icon(iconFor(element), x + 2, y + h / 2F, 0F, 0.5F);

            int color = enabled ? (hover ? Colors.HIGHLIGHT : Colors.WHITE) : Colors.GRAY;

            context.batcher.textShadow(this.elementToString(context, i, element), x + 20, y + (h - context.batcher.getFont().getHeight()) / 2, color);
        }
    }
}
