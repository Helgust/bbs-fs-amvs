package mchorse.bbs_mod.ui.framework;

import mchorse.bbs_mod.ui.forms.editors.UIFormEditor;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only tree invariant checks (Phase 4 item 3b of UI_BUGFIX_PLAN.md).
 *
 * <p>{@code UIDashboard} is a retained singleton, so a single mishandled event can leave the tree in
 * an internally inconsistent state that survives across reopens. This walks the live tree looking
 * for the specific corruptions that produce the reported "the UI just broke" symptoms:</p>
 *
 * <ul>
 *   <li>{@code activeElement} focused on an element that is no longer in the tree;</li>
 *   <li>a {@link UIFormEditor} whose {@code form}/{@code editor} pair is desynced (the editing
 *       invariant from Phase 1.4);</li>
 *   <li>a leaked modal focus scope (more scopes than open overlays).</li>
 * </ul>
 *
 * <p>Cheap enough to run on demand or from the dispatch-error path; not wired into the render loop.</p>
 */
public final class UIInvariants
{
    private UIInvariants()
    {}

    /**
     * Walk the menu's tree and return a human-readable list of invariant violations (empty when the
     * tree is healthy). Never throws - a checker that crashes would defeat its purpose.
     */
    public static List<String> check(UIBaseMenu menu)
    {
        List<String> problems = new ArrayList<>();

        if (menu == null)
        {
            return problems;
        }

        try
        {
            UIContext context = menu.context;

            /* 1. Orphaned focus: activeElement is a UIElement that no longer climbs to a root. */
            if (context.activeElement instanceof UIElement)
            {
                UIElement focused = (UIElement) context.activeElement;

                if (focused.getRoot() == null)
                {
                    problems.add("activeElement is focused but detached from the tree: " + focused.getClass().getSimpleName());
                }
            }

            /* 2. Form editors with a desynced form/editor pair. */
            for (UIFormEditor editor : menu.getRoot().getChildren(UIFormEditor.class))
            {
                if ((editor.form == null) != (editor.editor == null))
                {
                    problems.add("UIFormEditor form/editor desync: form=" + editor.form + ", editor=" + editor.editor);
                }
            }

            /* 3. Leaked modal scope: a scope survives with no overlay to own it. */
            int scopes = context.getModalScopeDepth();
            int overlays = menu.getRoot().getChildren(mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay.class).size();

            if (scopes > overlays)
            {
                problems.add("modal scope leak: " + scopes + " scope(s) open but only " + overlays + " overlay(s) in tree");
            }
        }
        catch (Throwable t)
        {
            problems.add("invariant check itself failed: " + t);
        }

        return problems;
    }

    /**
     * Run {@link #check(UIBaseMenu)} and log any violations as warnings. Returns true when the tree
     * is healthy (no problems found).
     */
    public static boolean checkAndLog(UIBaseMenu menu, Logger logger)
    {
        List<String> problems = check(menu);

        if (!problems.isEmpty() && logger != null)
        {
            logger.warn("BBS UI invariant check found {} problem(s):", problems.size());

            for (String problem : problems)
            {
                logger.warn("  - {}", problem);
            }
        }

        return problems.isEmpty();
    }
}
