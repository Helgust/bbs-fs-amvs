package mchorse.bbs_mod.ui.framework.elements;

import mchorse.bbs_mod.ui.framework.UIContext;

/**
 * Implemented by elements that own a dismissable modal layer (overlays, context menus, palettes,
 * third-party panels registered via {@code RegisterDashboardPanelsEvent}).
 *
 * <p>The central ESC router in {@link mchorse.bbs_mod.ui.framework.UIBaseMenu#handleEscape()} walks
 * the overlay layer top-down looking for the innermost {@code IUICloseHandler} and asks it to close,
 * instead of hardcoding concrete overlay types. This is Phase 4 item 2 of {@code UI_BUGFIX_PLAN.md}:
 * it lets any modal on the overlay layer get correct "ESC closes the topmost thing" behaviour for
 * free, so the router no longer has to know about every overlay subclass.</p>
 */
public interface IUICloseHandler
{
    /**
     * Attempt to close/dismiss the one layer this element owns.
     *
     * @return {@code true} if something was closed (the ESC press is consumed); {@code false} to let
     *         the router keep looking (e.g. this handler had nothing to close right now).
     */
    boolean requestClose(UIContext context);
}
