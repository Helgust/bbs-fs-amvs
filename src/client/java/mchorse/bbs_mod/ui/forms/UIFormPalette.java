package mchorse.bbs_mod.ui.forms;

import mchorse.bbs_mod.forms.categories.FormCategory;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.forms.categories.UIFormCategory;
import mchorse.bbs_mod.ui.forms.editors.UIFormEditor;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.EventPropagation;
import mchorse.bbs_mod.BBSSettings;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

public class UIFormPalette extends UIElement implements IUIFormList
{
    public UIFormList list;
    public UIFormEditor editor;

    public Consumer<Form> callback;

    /* The category + form being edited, held as the underlying data objects (not the UIFormCategory
     * wrapper). UIFormList.setupForms() rebuilds the wrappers whenever the categories reload mid-edit,
     * which would strand a wrapper reference on a stale object and mis-resolve the replace index. */
    private FormCategory lastCategory;
    private Form lastForm;
    private boolean background = true;
    private boolean cantExit;
    private boolean immersive;
    private boolean canModify;

    public static UIFormPalette open(UIElement parent, boolean editing, Form form, Consumer<Form> callback)
    {
        return open(parent, editing, form, false, callback);
    }

    public static UIFormPalette open(UIElement parent, boolean editing, Form form, boolean ignore, Consumer<Form> callback)
    {
        UIContext context = parent.getContext();

        /* Guard the missing context unconditionally: the ignore=true path used to skip this and then
         * NPE on context.unfocus() below (see UISelectorsOverlayPanel). */
        if (context == null)
        {
            return null;
        }

        if (!ignore && !parent.getRoot().getChildren(UIFormPalette.class).isEmpty())
        {
            return null;
        }

        context.unfocus();

        UIFormPalette palette = new UIFormPalette(callback);

        palette.resetFlex().full(parent);
        palette.resize();

        parent.add(palette);

        palette.setSelected(form);
        palette.edit(editing);

        return palette;
    }

    public UIFormPalette(Consumer<Form> callback)
    {
        this.callback = callback;

        this.list = new UIFormList(this);
        this.list.full(this);

        this.editor = new UIFormEditor(this);
        this.editor.full(this);
        this.editor.setVisible(false);

        this.add(this.list, this.editor);

        this.eventPropagataion(EventPropagation.BLOCK_INSIDE).markContainer();

        this.keys().register(Keys.FORMS_EDIT, () ->
        {
            if (!this.editor.isEditing())
            {
                this.toggleEditor();
            }
        });
    }

    public void noBackground()
    {
        this.background = false;
    }

    public void cantExit()
    {
        this.cantExit = true;

        this.list.close.removeFromParent();
        this.eventPropagataion(EventPropagation.PASS);
    }

    public void canModify()
    {
        this.canModify = true;
    }

    public boolean isImmersive()
    {
        return this.immersive;
    }

    public void immersive()
    {
        this.immersive = true;
    }

    public UIFormPalette updatable()
    {
        this.editor.renderer.updatable();

        return this;
    }

    public void edit(boolean editing)
    {
        if (editing != this.editor.isEditing())
        {
            this.toggleEditor();
        }
    }

    @Override
    public void exit()
    {
        if (!this.editor.isEditing())
        {
            if (!this.cantExit)
            {
                this.removeFromParent();
            }
        }
        else
        {
            this.toggleEditor();
        }
    }

    @Override
    public void toggleEditor()
    {
        boolean wasEditing = this.editor.isEditing();

        if (!wasEditing)
        {
            Form form = this.list.getSelected();
            UIFormCategory category = this.list.getSelectedCategory();

            if (this.editor.edit(form))
            {
                this.lastCategory = category == null ? null : category.category;
                this.lastForm = category == null ? null : category.selected;
            }
            else
            {
                /* Entering edit failed (null form / unregistered form class). Stay in list mode and
                 * don't strand any edit state. */
                this.lastCategory = null;
                this.lastForm = null;
            }
        }
        else
        {
            Form form = this.editor.finish();

            /* Resolve the index at finish time against the live data objects: the UI wrapper may have
             * been rebuilt mid-edit, and canModify guards the whole write. */
            if (this.canModify && this.lastCategory != null && this.lastCategory.canModify(form))
            {
                int index = this.lastCategory.getForms().indexOf(this.lastForm);

                if (index >= 0)
                {
                    this.lastCategory.replaceForm(index, form);
                }
            }

            this.list.setSelected(form);
            this.accept(form);

            this.lastCategory = null;
            this.lastForm = null;
        }

        boolean nowEditing = this.editor.isEditing();

        /* Emit only on an actual state change, with the real post-transition state. Emitting a
         * predicted state up front (before edit() could fail) left listeners believing editing had
         * started with no matching end event - e.g. a leaked camera controller in the model block. */
        if (wasEditing != nowEditing)
        {
            this.events.emit(new UIToggleEditorEvent(this, nowEditing));
        }

        this.list.setVisible(!nowEditing);
        this.editor.setVisible(nowEditing);
    }

    @Override
    public void accept(Form form)
    {
        if (this.callback != null)
        {
            this.callback.accept(form);
        }
    }

    public void setSelected(Form form)
    {
        this.list.setSelected(form);
        this.list.scrollToSelected();
    }

    @Override
    public boolean subKeyPressed(UIContext context)
    {
        if (context.isPressed(GLFW.GLFW_KEY_ESCAPE))
        {
            boolean wasEditing = this.editor.isEditing();

            this.exit();

            if (!this.cantExit)
            {
                return true;
            }

            if (wasEditing)
            {
                return true;
            }
        }

        return false;
    }

    @Override
    public void render(UIContext context)
    {
        if (this.background)
        {
            if (!this.immersive || this.list.isVisible())
            {
                this.area.render(context.batcher, BBSSettings.baseSurface());
            }
        }

        /* Panels are the deep surface, so inputs take the raised (lighter) surface to stand out -
         * same as the film editor. Sections flip this back to false for their own inner inputs. */
        boolean lightInputs = BBSSettings.lightInputs;

        BBSSettings.lightInputs = true;

        try
        {
            super.render(context);
        }
        finally
        {
            BBSSettings.lightInputs = lightInputs;
        }
    }
}