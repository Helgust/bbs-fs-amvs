package mchorse.bbs_mod.ui.framework;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.framework.elements.IUICloseHandler;
import mchorse.bbs_mod.ui.framework.elements.IUIElement;
import mchorse.bbs_mod.ui.framework.elements.IViewport;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.utils.IViewportStack;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.renderers.InputRenderer;
import mchorse.bbs_mod.utils.colors.Colors;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

/**
 * Base class for GUI screens using this framework
 */
public abstract class UIBaseMenu
{
    private static final Logger LOGGER = LogUtils.getLogger();

    /** F8 toggle for the transform gizmo / axes. Read through {@link #shouldRenderAxes()}, which also
     *  honours the hold-to-hide key, rather than directly. */
    public static boolean renderAxes = true;

    private static InputRenderer inputRenderer = new InputRenderer();

    /**
     * Whether the transform gizmo / axes should be drawn (and pickable) right now: the F8 toggle
     * {@link #renderAxes} is on AND the hold-to-hide key ({@link Keys#TRANSFORMATIONS_HIDE_GIZMO}) is
     * not being held. Gizmo render, its stencil pass and picking all gate on this, so holding the key
     * hides everything at once. The held state is polled (the keybind system only dispatches presses,
     * not holds).
     */
    public static boolean shouldRenderAxes()
    {
        return renderAxes && !isHideGizmoHeld();
    }

    /** Whether the hold-to-hide gizmo key ({@link Keys#TRANSFORMATIONS_HIDE_GIZMO}) is currently held.
     *  Use this to suppress gizmo stencil/picking at sites that aren't otherwise gated by the F8
     *  {@link #renderAxes} flag, so the hold removes them without altering F8's behaviour. */
    public static boolean isHideGizmoHeld()
    {
        return Keys.TRANSFORMATIONS_HIDE_GIZMO.isHeld();
    }

    private UIRootElement root;
    public UIElement main;
    public UIElement overlay;
    public UIContext context;
    public Area viewport = new Area();

    public int width;
    public int height;

    public UIBaseMenu()
    {
        this.context = new UIContext(this);

        this.root = new UIRootElement(this.context);
        this.root.markContainer().full(this.viewport);

        this.main = new UIElement();
        this.main.full(this.viewport);
        this.overlay = new UIElement();
        this.overlay.full(this.viewport);
        this.root.add(this.main, this.overlay);

        UIElement popka = new UIElement();

        popka.keys().register(Keys.KEYBINDS, () -> this.context.toggleKeybinds());
        popka.keys().register(Keys.TRANSFORMATIONS_TOGGLE_AXES, () -> renderAxes = !renderAxes);
        this.root.add(popka);

        this.context.keybinds.relative(this.viewport).wh(0.5F, 1F);
    }

    public UIRootElement getRoot()
    {
        return this.root;
    }

    public boolean canHideHUD()
    {
        return true;
    }

    public boolean canPause()
    {
        return true;
    }

    public boolean canRefresh()
    {
        return true;
    }

    public void onOpen(UIBaseMenu oldMenu)
    {}

    public void onClose(UIBaseMenu nextMenu)
    {}

    public void update()
    {
        this.context.update();
    }

    public void resize(int width, int height)
    {
        this.width = width;
        this.height = height;

        this.viewport.set(0, 0, this.width, this.height);
        this.viewportSet();

        this.context.pushViewport(this.viewport);
        this.root.resize();
        this.context.popViewport();
    }

    protected void viewportSet()
    {}

    public boolean mouseClicked(int mouseX, int mouseY, int mouseButton)
    {
        boolean result = false;

        this.context.setMouse(mouseX, mouseY, mouseButton);

        if (this.root.isEnabled())
        {
            this.context.pushViewport(this.viewport);

            try
            {
                IUIElement element = this.root.mouseClicked(this.context);

                result = element != null;
            }
            catch (Throwable t)
            {
                this.handleDispatchError("mouseClicked", t);
            }
            finally
            {
                this.context.popViewport();
            }
        }

        return result;
    }

    public boolean mouseScrolled(int x, int y, double v)
    {
        boolean result = false;

        this.context.setMouseWheel(x, y, v, this.context.mouseWheelHorizontal);

        if (this.root.isEnabled())
        {
            this.context.pushViewport(this.viewport);

            try
            {
                IUIElement element = this.root.mouseScrolled(this.context);

                result = element != null;
            }
            catch (Throwable t)
            {
                this.handleDispatchError("mouseScrolled", t);
            }
            finally
            {
                this.context.popViewport();
            }
        }

        return result;
    }

    public boolean mouseReleased(int mouseX, int mouseY, int mouseButton)
    {
        boolean result = false;

        this.context.setMouse(mouseX, mouseY, mouseButton);

        if (this.root.isEnabled())
        {
            this.context.pushViewport(this.viewport);

            try
            {
                IUIElement element = this.root.mouseReleased(this.context);

                result = element != null;
            }
            catch (Throwable t)
            {
                this.handleDispatchError("mouseReleased", t);
            }
            finally
            {
                this.context.popViewport();
            }
        }

        Gizmo.INSTANCE.stop();

        return result;
    }

    public boolean handleKey(int key, int scanCode, int action, int mods)
    {
        if (action == GLFW.GLFW_PRESS)
        {
            inputRenderer.keyPressed(this.context, key);
        }

        this.context.setKeyEvent(key, scanCode, action);

        boolean enabled = this.root.isEnabled();

        try
        {
            /* ESC is routed centrally before the generic tree walk: close exactly one thing, innermost
             * first (focus -> context menu -> topmost overlay), and only fall through to the tree (which
             * lets the form palette / editor / keyframes cancel their own gestures) when none of those
             * apply. This kills the "focus stranded under a BLOCKing overlay" dead-ESC deadlock and stops
             * a dropped link in the old decentral chain from nuking the whole dashboard. */
            if (enabled && this.context.isPressed(GLFW.GLFW_KEY_ESCAPE) && this.handleEscape())
            {
                return true;
            }

            /* Only dispatch (and honour side effects) into an enabled root. */
            IUIElement element = enabled ? this.root.keyPressed(this.context) : null;

            if (element != null)
            {
                return true;
            }
        }
        catch (Throwable t)
        {
            this.handleDispatchError("handleKey", t);

            /* Swallow the press: an ESC that blew up mid-dispatch must NOT fall through to the
             * close-dashboard fallback below and nuke a shared, now half-broken dashboard. */
            return true;
        }

        if (this.context.isPressed(GLFW.GLFW_KEY_ESCAPE))
        {
            this.closeMenu();

            return true;
        }

        return false;
    }

    /**
     * Central handler for an exception thrown while dispatching a UI input event. Because the
     * dashboard is a retained singleton (arch doc §8), letting the exception propagate into the
     * vanilla {@code Screen} crashes the game or half-applies the event and leaves the shared tree
     * permanently corrupted. Instead we log it, notify the user, and (in dev) dump the invariant
     * state that most often explains such a crash. See Phase 4 item 3 of UI_BUGFIX_PLAN.md.
     */
    private void handleDispatchError(String phase, Throwable t)
    {
        LOGGER.error("BBS UI: uncaught exception while dispatching {}", phase, t);

        try
        {
            this.context.notifyError(IKey.raw("UI error during " + phase + " (see log): " + t));
        }
        catch (Throwable ignored)
        {}

        if (net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment())
        {
            UIInvariants.checkAndLog(this, LOGGER);
        }
    }

    /**
     * Central ESC "close topmost" pass. Returns true when it consumed the press (so the tree walk and
     * the close-dashboard fallback are skipped). Returns false to let the tree handle ESC itself
     * (form palette exit-editor / close, keyframe cancel, etc.).
     */
    private boolean handleEscape()
    {
        UIOverlay overlay = this.getTopmostOverlay();

        /* 1. A focused element: unfocus it. If the focus is stranded *outside* the topmost overlay
         * (the deadlock case - the overlay BLOCKs keys so it can never be reached or closed), also
         * proceed to close that overlay in the same press. Focus inside the overlay just unfocuses. */
        if (this.context.isFocused())
        {
            boolean insideOverlay = overlay != null && this.isFocusInside(overlay);

            this.context.unfocus();

            if (overlay == null || insideOverlay)
            {
                return true;
            }
        }

        /* 2. Context menu (sits above overlays). */
        if (this.context.hasContextMenu())
        {
            this.context.closeContextMenu();

            return true;
        }

        /* 3. Topmost modal close handler on the overlay layer. This is any IUICloseHandler (overlays
         * and third-party modals alike), so the router no longer hardcodes overlay types - Phase 4
         * item 2. */
        IUICloseHandler handler = this.getTopmostCloseHandler();

        if (handler != null && handler.requestClose(this.context))
        {
            return true;
        }

        /* 4. Nothing modal to close - let the tree walk handle ESC. */
        return false;
    }

    private UIOverlay getTopmostOverlay()
    {
        java.util.List<IUIElement> children = this.overlay.getChildren();

        for (int i = children.size() - 1; i >= 0; i--)
        {
            if (children.get(i) instanceof UIOverlay)
            {
                return (UIOverlay) children.get(i);
            }
        }

        return null;
    }

    /**
     * The topmost dismissable modal on the overlay layer (an overlay, or any third-party
     * {@link IUICloseHandler}). Context menus sit above overlays and are handled in the step before
     * this one, so they are not returned here in practice.
     */
    private IUICloseHandler getTopmostCloseHandler()
    {
        java.util.List<IUIElement> children = this.overlay.getChildren();

        for (int i = children.size() - 1; i >= 0; i--)
        {
            if (children.get(i) instanceof IUICloseHandler)
            {
                return (IUICloseHandler) children.get(i);
            }
        }

        return null;
    }

    private boolean isFocusInside(UIOverlay overlay)
    {
        if (!(this.context.activeElement instanceof UIElement))
        {
            return false;
        }

        UIElement focused = (UIElement) this.context.activeElement;

        return overlay == focused || overlay.isDescendant(focused);
    }

    public void handleTextInput(int key)
    {
        this.context.setKeyTyped((char) key);

        if (this.root.isEnabled())
        {
            try
            {
                this.root.textInput(this.context);
            }
            catch (Throwable t)
            {
                this.handleDispatchError("handleTextInput", t);
            }
        }
    }

    /**
     * This method is called when this screen is about to get closed
     */
    protected void closeMenu()
    {
        MinecraftClient.getInstance().setScreen(null);
    }

    public void closeThisMenu()
    {
        this.closeMenu();
    }

    public void renderDefaultBackground()
    {
        this.context.batcher.box(0, 0, this.width, this.height, Colors.A50);
    }

    public void renderMenu(UIRenderingContext context, int mouseX, int mouseY)
    {
        RenderSystem.depthFunc(GL11.GL_ALWAYS);

        this.context.resetMatrix();
        this.context.setMouse(mouseX, mouseY);
        this.context.resetCursor();

        this.preRenderMenu(context);

        if (this.root.isVisible())
        {
            this.context.reset();
            this.context.pushViewport(this.viewport);

            this.root.render(this.context);

            this.context.popViewport();
            this.context.postRender();
        }

        if (this.main.isVisible())
        {
            inputRenderer.render(this, mouseX, mouseY);
        }

        this.context.applyCursor();

        RenderSystem.depthFunc(GL11.GL_LEQUAL);
    }

    protected void preRenderMenu(UIRenderingContext context)
    {}

    public void startRenderFrame(float tickDelta)
    {}

    public void renderInWorld(WorldRenderContext context)
    {}

    public static class UIRootElement extends UIElement implements IViewport
    {
        private UIContext context;

        public UIRootElement(UIContext context)
        {
            super();

            this.context = context;

            this.markContainer();
        }

        public UIContext getContext()
        {
            return this.context;
        }

        @Override
        public void apply(IViewportStack stack)
        {
            stack.pushViewport(this.area);
        }

        @Override
        public void unapply(IViewportStack stack)
        {
            stack.popViewport();
        }
    }
}
