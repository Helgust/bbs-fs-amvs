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
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlayPanel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;

/**
 * Offset window for the selected replays, extended with the non-destructive
 * processing stack ({@link mchorse.bbs_mod.film.replays.ReplayProcessing}) of the
 * focused replay.
 *
 * The top input applies a time offset to the whole selection (recording it onto
 * each replay's stack). Below it the stack list shows the focused replay's
 * recorded operations with drag-reorder, enable toggle and delete, plus Flatten
 * (bake the result and drop the stack) and Clear (restore the base). Every edit
 * re-bakes the replay so the result updates live.
 */
public class UIReplayProcessingOverlay extends UIOverlayPanel
{
    private final UIReplayList replays;
    private final UIFilmPanel panel;
    private final Replay replay;

    private final UITextbox offset;
    private final OpList list;

    public UIReplayProcessingOverlay(UIReplayList replays, Replay replay)
    {
        super(UIKeys.SCENE_REPLAYS_CONTEXT_OFFSET_TIME_TITLE);

        this.replays = replays;
        this.panel = replays.panel;
        this.replay = replay;

        this.offset = new UITextbox((t) -> UIReplayList.LAST_OFFSET = t);
        this.offset.setText(UIReplayList.LAST_OFFSET);
        this.offset.tooltip(UIKeys.SCENE_REPLAYS_CONTEXT_OFFSET_TIME_EXPRESSION_TOOLTIP);

        UIButton apply = new UIButton(UIKeys.SCENE_REPLAYS_PROCESSING_APPLY_OFFSET, (b) -> this.applyOffset());

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

        UIButton flatten = new UIButton(UIKeys.SCENE_REPLAYS_PROCESSING_FLATTEN, (b) -> this.flatten());
        flatten.tooltip(UIKeys.SCENE_REPLAYS_PROCESSING_FLATTEN_TOOLTIP);

        UIButton clear = new UIButton(UIKeys.SCENE_REPLAYS_PROCESSING_CLEAR, (b) -> this.clear());
        clear.tooltip(UIKeys.SCENE_REPLAYS_PROCESSING_CLEAR_TOOLTIP);

        UIElement buttons = UI.row(clear, flatten);

        this.offset.relative(this.content).xy(6, 6).w(1F, -12).h(20);
        apply.relative(this.content).x(6).y(30).w(1F, -12).h(20);
        this.list.relative(this.content).x(6).y(54).w(1F, -12).h(1F, -80);
        buttons.relative(this.content).x(6).y(1F, -26).w(1F, -12).h(20);

        this.content.add(this.offset, apply, this.list, buttons);

        this.refresh();
    }

    private void applyOffset()
    {
        this.replays.applyTimeOffset(this.offset.getText());
        this.refresh();
    }

    private void toggle(ReplayProcessingOp op)
    {
        Film film = this.panel.getData();

        BaseValue.edit(this.replay, IValueListener.FLAG_UNMERGEABLE, (r) ->
        {
            op.enabled.set(!op.enabled.get());
            ReplayProcessingBaker.rebake(film, r);
        });

        this.afterChange();
    }

    private void delete(ReplayProcessingOp op)
    {
        Film film = this.panel.getData();

        BaseValue.edit(this.replay, IValueListener.FLAG_UNMERGEABLE, (r) ->
        {
            r.processing.remove(op);
            ReplayProcessingBaker.rebake(film, r);
        });

        this.afterChange();
    }

    private void flatten()
    {
        BaseValue.edit(this.replay, IValueListener.FLAG_UNMERGEABLE, (r) -> r.processing.flatten());

        this.afterChange();
    }

    private void clear()
    {
        Film film = this.panel.getData();

        BaseValue.edit(this.replay, IValueListener.FLAG_UNMERGEABLE, (r) -> r.processing.clear(r));

        this.afterChange();
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
