/* Glazed Lists                                                 (c) 2003-2006 */
/* http://publicobject.com/glazedlists/                      publicobject.com,*/
/*                                                     O'Dell Engineering Ltd.*/
package ca.odell.glazedlists.swing;

import ca.odell.glazedlists.EventList;
import ca.odell.glazedlists.FilterList;
import ca.odell.glazedlists.GlazedLists;
import ca.odell.glazedlists.TextFilterator;
import ca.odell.glazedlists.impl.filter.TextMatcher;
import ca.odell.glazedlists.matchers.Matcher;
import ca.odell.glazedlists.matchers.Matchers;
import ca.odell.glazedlists.matchers.TextMatcherEditor;

import javax.accessibility.Accessible;
import javax.swing.*;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import javax.swing.event.PopupMenuListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.plaf.ComboBoxUI;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.ComboPopup;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.ActionEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

/**
 * This class installs support for filtering and autocompletion into a standard
 * {@link JComboBox}. All behaviour provided is meant to mimic the
 * functionality of the Firefox address field. To be explicit, the following is
 * a list of expected behaviours which are installed:
 *
 * <p><strong>Typing into the ComboBox Editor</strong>
 * <ol>
 *   <li> typing any value into the editor when the popup is invisible causes
 *        the popup to appear and its contents to be filtered according to the
 *        editor's text. It also autocompletes to the first item that is
 *        prefixed with the editor's text and selects that item within the popup.
 *   <li> typing any value into the editor when the popup is visible causes
 *        the popup to be refiltered to contain values that start with the prefix
 *        and reselects an appropriate autocompletion item
 *   <li> typing the down or up arrow keys in the editor when the popup is
 *        invisible causes the popup to appear and its contents to be filtered
 *        according to the editor's text. It also autocompletes to the first
 *        item that is prefixed with the editor's text and selects that item
 *        within the popup.
 *   <li> typing the up arrow key when the popup is visible and the selected
 *        element is the first element causes the autocompletion to be cleared
 *        and the popup's selection to be removed
 *   <li> typing the up arrow key when no selection exists causes the last
 *        element of the popup to become selected and used for autocompletion
 *   <li> typing the down arrow key when the popup is visible and the selected
 *        element is the last element causes the autocompletion to be cleared
 *        and the popup's selection to be removed
 *   <li> typing the down arrow key when no selection exists causes the first
 *        element of the popup to become selected and used for autocompletion
 * </ol>
 *
 * <strong>Clicking on the Arrow Button</strong>
 * <ol>
 *   <li> clicking the arrow button when the popup is invisible causes the
 *        popup to appear and its contents to be shown unfiltered
 *   <li> clicking the arrow button when the popup is visible causes the popup
 *        to disappear
 * </ol>
 *
 * <strong>Sizing the ComboBox Popup</strong>
 * <ol>
 *   <li> the popup is always <strong>at least</strong> as wide as the
 *        autocompleting {@link JComboBox}, but may grow wider to accomodate a
 *        {@link JComboBox#getPrototypeDisplayValue()} if a non-null value is
 *        returned.
 *   <li> as items are filtered in the ComboBoxModel, the popup height is
 *        adjusted to display between 0 and
 *        {@link JComboBox#getMaximumRowCount()} rows before resorting to
 *        scrolling the popup.
 * </ol>
 *
 * In order to achieve all of the autocompletion and filtering behaviour, the
 * following occurs when {@link #install} is called:
 *
 * <ul>
 *   <li> the JComboBox will be made editable
 *   <li> the JComboBox will have a custom UI delegate installed on it
 *        that decorates the existing UI delegate
 *   <li> the JComboBox will have a custom ComboBoxModel installed on it
 *        containing the given items
 *   <li> the JTextField which is the editor component for the JComboBox
 *        will have a DocumentFilter installed on its backing Document
 * </ul>
 *
 * The strategy of this support class is to alter all of the objects which
 * influence the behaviour of the JComboBox in one single context. With that
 * achieved, it greatly reduces the cross-functional communication required to
 * customize the behaviour of JComboBox for filtering and autocompletion.
 *
 * @author James Lemieux
 */
public final class AutoCompleteSupport<E> {

    //
    // These member variables control behaviour of the autocompletion support
    //

    /**
     * <tt>true</tt> if user specified text is converted into the same case as
     * the matched element. <tt>false</tt> will leave user specified text
     * unaltered.
     */
    private boolean correctsCase = true;

    //
    // These are member variables for convenience
    //

    /** The comboBox being decorated with autocomplete functionality. */
    private JComboBox comboBox;

    /** The model backing the comboBox. */
    private final AutoCompleteComboBoxModel comboBoxModel;

    /** The FilterList which holds the items present in the comboBoxModel. */
    private final FilterList<E> filteredItems;

    /** The MatcherEditor driving the FilterList behind the comboBoxModel. */
    private final TextMatcherEditor<E> filterMatcherEditor;

    /** The textfield which acts as the editor of the comboBox. */
    private final JTextField comboBoxEditor;

    /** The Document backing the comboBoxEditor. */
    private AbstractDocument document;

    /** A DocumentFilter that controls edits to the Document behind the comboBoxEditor. */
    private final AutoCompleteFilter documentFilter = new AutoCompleteFilter();

    /** The last prefix specified by the user. */
    private String prefix = "";

    /** This matcher determines if the user-defined prefix matches the beginning of a given String. */
    private Matcher<String> prefixMatcher = Matchers.trueMatcher();

    //
    // These listeners watch for invariant violations in the JComboBox and report them
    //

    /**
     * Watches for changes of the Document which backs comboBoxEditor and uninstalls
     * our DocumentFilter from the old Document and reinstalls it on the new.
     */
    private final DocumentWatcher documentWatcher = new DocumentWatcher();

    /** Watches for changes of the ComboBoxModel and reports them as violations. */
    private final ModelWatcher modelWatcher = new ModelWatcher();

    /** Watches for changes of the ComboBoxUI and reports them as violations. */
    private final UIWatcher uiWatcher = new UIWatcher();

    //
    // These booleans control when certain changes are to be respected and when they aren't
    //

    /** <tt>true</tt> indicates document changes should not be post processed
     * (i.e. just commit changes to the Document and do not cause any side-effects). */
    private boolean doNotPostProcessDocumentChanges = false;

    /** <tt>true</tt> indicates attempts to filter the combo box model should be ignored. */
    private boolean doNotFilter = false;

    /** <tt>true</tt> indicates attempts to change the document should be ignored. */
    private boolean doNotChangeDocument = false;

    //
    // Values present when install() executed - these are restored in uninstall()
    //

    /** The original setting of the editable field on the comboBox. */
    private final boolean originalComboBoxEditable;

    /** The original editor for the comboBox. */
    private ComboBoxEditor originalComboBoxEditor;

    /** The original model installed on the comboBox. */
    private ComboBoxModel originalModel;

    /** The original UI delegate installed on the comboBox. */
    private ComboBoxUI originalUI;

    /**
     * This private constructor creates an AutoCompleteSupport object which adds
     * autocompletion functionality to the given <code>comboBox</code>. In
     * particular, a custom {@link ComboBoxModel} is installed behind the
     * <code>comboBox</code> containing the given <code>items</code>. The
     * <code>filterator</code> is consulted in order to extract searchable
     * text from each of the <code>items</code>.
     *
     * @param comboBox the {@link JComboBox} to decorate with autocompletion
     * @param items the objects to display in the <code>comboBox</code>
     * @param filterator extracts searchable text strings from each item
     */
    private AutoCompleteSupport(JComboBox comboBox, EventList<E> items, TextFilterator<E> filterator) {
        this.comboBox = comboBox;

        // record some original settings of comboBox
        this.originalUI = comboBox.getUI();
        this.originalComboBoxEditable = comboBox.isEditable();
        this.originalModel = comboBox.getModel();
        this.originalComboBoxEditor = comboBox.getEditor();

        // build the ComboBoxModel capable of filtering its values
        this.filterMatcherEditor = new TextMatcherEditor<E>(filterator);
        this.filterMatcherEditor.setMode(TextMatcherEditor.STARTS_WITH);
        this.filteredItems = new FilterList<E>(items, filterMatcherEditor);
        this.comboBoxModel = new AutoCompleteComboBoxModel(filteredItems);

        // customize the comboBox
        this.comboBox.setModel(this.comboBoxModel);
        this.comboBox.setUI(new AutoCompleteComboBoxUI((BasicComboBoxUI) this.comboBox.getUI()));
        this.comboBox.setEditable(true);

        // add a DocumentFilter to the Document backing the editor JTextField
        this.comboBoxEditor = (JTextField) comboBox.getEditor().getEditorComponent();
        this.document = (AbstractDocument) this.comboBoxEditor.getDocument();
        this.document.setDocumentFilter(this.documentFilter);

        // detect changes made to the key parts of JComboBox which must be controlled for autocompletion
        this.comboBox.addPropertyChangeListener("UI", this.uiWatcher);
        this.comboBox.addPropertyChangeListener("model", this.modelWatcher);
        this.comboBoxEditor.addPropertyChangeListener("document", this.documentWatcher);
    }

    /**
     * Installs support for autocompletion into the <code>comboBox</code> and
     * returns the support object that is actually providing those facilities.
     * The support object is returned so that the caller may invoke
     * {@link #uninstall} at some later time to remove the autocompletion
     * features.
     *
     * <p>This method assumes that the <code>items</code> can be converted into
     * reasonable String representations via {@link Object#toString()}.
     *
     * @param comboBox the {@link JComboBox} to decorate with autocompletion
     * @param items the objects to display in the <code>comboBox</code>
     * @return an instance of the support class that is providing autocomplete
     *      features
     */
    public static <E> AutoCompleteSupport install(JComboBox comboBox, EventList<E> items) {
        return install(comboBox, items, GlazedLists.toStringTextFilterator());
    }

    /**
     * Installs support for autocompletion into the <code>comboBox</code> and
     * returns the support object that is actually providing those facilities.
     * The support object is returned so that the caller may invoke
     * {@link #uninstall} at some later time to remove the autocompletion
     * features.
     *
     * <p>The <code>filterator</code> will be used to extract searchable text
     * strings from each of the <code>items</code>.
     *
     * The following must be true in order to successfully install support for
     * autocompletion on a {@link JComboBox}:
     *
     * <ul>
     *   <li> The JComboBox must use a {@link JTextField} as its editor
     *   <li> The JTextField must use an {@link AbstractDocument} as its model
     *   <li> The JComboBox UI delegate must be a subclass of {@link BasicComboBoxUI}
     * </ul>
     *
     * @param comboBox the {@link JComboBox} to decorate with autocompletion
     * @param items the objects to display in the <code>comboBox</code>
     * @param filterator extracts searchable text strings from each item
     * @return an instance of the support class that is providing autocomplete
     *      features
     */
    public static <E> AutoCompleteSupport<E> install(JComboBox comboBox, EventList<E> items, TextFilterator<E> filterator) {
        if (!(comboBox.getUI() instanceof BasicComboBoxUI))
            throw new IllegalArgumentException("comboBox UI must be a subclass of " + BasicComboBoxUI.class);

        final Component editorComponent = comboBox.getEditor().getEditorComponent();
        if (!(editorComponent instanceof JTextField))
            throw new IllegalArgumentException("comboBox must use a JTextField as its editor component");

        if (!(((JTextField) editorComponent).getDocument() instanceof AbstractDocument))
            throw new IllegalArgumentException("comboBox must use a JTextField backed by an AbstractDocument as its editor component");

        return new AutoCompleteSupport<E>(comboBox, items, filterator);
    }

    /**
     * This method is used to report environmental invariants which are
     * violated when the user adjusts the combo box in a way that is
     * incompatible with the requirements for autocompletion. A message can be
     * specified which will be included in the {@link IllegalStateException}
     * that is throw out of this method after the autocompletion support is
     * uninstalled.
     *
     * @param message a message to the programmer explaining the environmental
     *      invariant that was violated
     */
    private void throwIllegalStateException(String message) {
        final String exceptionMsg = message + "\n" +
                "In order for AutoCompleteSupport to continue to " +
                "work, the following invariants must be maintained after " +
                "AutoCompleteSupport.install() has been called:\n" +
                "* the ComboBoxModel may not be removed\n" +
                "* the ComboBoxUI may not be removed\n" +
                "* the ComboBoxEditor may not be removed\n" +
                "* the AbstractDocument behind the JTextField can be changed but must be changed to some subclass of AbstractDocument\n" +
                "* the DocumentFilter on the AbstractDocument behind the JTextField may not be removed\n";

        uninstall();

        throw new IllegalStateException(exceptionMsg);
    }

    /**
     * Returns the autocompleting {@link JComboBox} or <code>null</code> if
     * {@link AutoCompleteSupport} has been {@link #uninstall}ed.
     */
    public JComboBox getComboBox() {
        return this.comboBox;
    }

    /**
     * Returns the filtered {@link EventList} of items which backs the
     * {@link ComboBoxModel} of the autocompleting {@link JComboBox}.
     */
    public EventList<E> getItemList() {
        return this.filteredItems;
    }

    /**
     * Return <tt>true</tt> if user specified strings are converted to the
     * case of the element they match; <tt>false</tt> otherwise.
     */
    public boolean getCorrectsCase() {
        return correctsCase;
    }
    /**
     * If <code>correctCase</code> is <tt>true</tt>, user specified strings
     * will be converted to the case of the element they match. Otherwise
     * they will be left unaltered.
     */
    public void setCorrectsCase(boolean correctCase) {
        this.correctsCase = correctCase;
    }

    /**
     * This method removes autocompletion support from the {@link JComboBox}
     * it was installed on. This method is useful when the {@link EventList} of
     * items that backs the combo box must outlive the combo box itself.
     * Calling this method will return the combo box to its original state
     * before autocompletion was installed, and it will be available for
     * garbage collection independently of the {@link EventList} of items.
     */
    public void uninstall() {
        if (this.comboBox == null)
            throw new IllegalStateException("This AutoCompleteSupport has already been uninstalled");

        // 1. stop listening for changes
        this.comboBox.removePropertyChangeListener("UI", this.uiWatcher);
        this.comboBox.removePropertyChangeListener("model", this.modelWatcher);
        this.comboBoxEditor.removePropertyChangeListener("document", this.documentWatcher);

        // 2. restore the original model to the JComboBox
        this.comboBox.setModel(this.originalModel);
        this.originalModel = null;

        // 3. restore the original UI delegate to the JComboBox
        this.comboBox.setUI(this.originalUI);
        this.originalUI = null;

        // 5. restore the original ComboBoxEditor to the JComboBox
        this.comboBox.setEditor(this.originalComboBoxEditor);
        this.originalComboBoxEditor = null;

        // 6. restore the original editable flag to the JComboBox
        this.comboBox.setEditable(originalComboBoxEditable);

        // 7. dispose of our FilterList so that it is severed from the given items EventList
        this.filteredItems.dispose();

        // null out the comboBox to indicate that this support class is uninstalled
        this.comboBox = null;
    }

    /**
     * This method updates the value which filters the items in the
     * ComboBoxModel.
     *
     * @param newFilter the new value by which to filter the item
     */
    private void applyFilter(String newFilter) {
        // break out early if we're flagged to ignore filter updates for the time being
        if (doNotFilter) return;

        // ignore attempts to change the text in the combo box editor while
        // the filtering is taking place
        doNotChangeDocument = true;
        try {
            filterMatcherEditor.setFilterText(new String[] {newFilter});
        } finally {
            doNotChangeDocument = false;
        }
    }

    /**
     * This method updates the {@link #prefix} to be the current value in the
     * combo box editor.
     */
    private void updatePrefix() throws BadLocationException {
        prefix = document.getText(0, document.getLength());

        if (prefix.length() == 0)
            prefixMatcher = Matchers.trueMatcher();
        else
            prefixMatcher = new TextMatcher<String>(new String[] {prefix}, GlazedLists.toStringTextFilterator(), TextMatcherEditor.STARTS_WITH);
    }

    /**
     * A small convenience method to try showing the combo box popup.
     */
    private void togglePopup() {
        if (comboBoxModel.getSize() == 0)
            comboBox.hidePopup();

        else if (comboBox.isShowing() && !comboBox.isPopupVisible())
            comboBox.showPopup();
    }

    /**
     * This special version of EventComboBoxModel simply marks a flag to
     * indicate the items in the ComboBoxModel should not be filtered as a
     * side-effect of setting the selected item.
     */
    private class AutoCompleteComboBoxModel extends EventComboBoxModel<E> {
        public AutoCompleteComboBoxModel(EventList<E> source) {
            super(source);
        }
        public void setSelectedItem(Object selected) {
            doNotFilter = true;
            try {
                super.setSelectedItem(selected);

                // Windows L&F likes to selectAll() text in the comboBoxEditor when a new item
                // is selected, but that interferes with our autocompletion usability, so we
                // clear the selection here
                final int caretPos = comboBoxEditor.getCaretPosition();
                comboBoxEditor.select(caretPos, caretPos);
            } finally {
                doNotFilter = false;
            }
        }
    }

    /**
     * This class is the crux of the entire solution. This custom
     * DocumentFilter controls all edits which are attempted against the combo
     * box editor's Document. It is our hook to either control when to respect
     * edits as well as the side-effects the edit has on autocompletion and
     * filtering.
     */
    private class AutoCompleteFilter extends DocumentFilter {
        public void replace(FilterBypass filterBypass, int offset, int length, String string, AttributeSet attributeSet) throws BadLocationException {
            // this short-circuit corrects the PlasticLookAndFeel behaviour. Hitting the enter key in Plastic
            // will cause the popup to reopen because the Plastic ComboBoxEditor forwards on unnecessary updates
            // to the document, including ones where the text isn't really changing
            if (offset == 0 && document.getLength() == length && string != null && string.equals(comboBoxEditor.getText()))
                return;

            if (doNotChangeDocument) return;
            super.replace(filterBypass, offset, length, string, attributeSet);
            postProcessDocumentChange(filterBypass, attributeSet);
        }

        public void insertString(FilterBypass filterBypass, int offset, String string, AttributeSet attributeSet) throws BadLocationException {
            if (doNotChangeDocument) return;
            super.insertString(filterBypass, offset, string, attributeSet);
            postProcessDocumentChange(filterBypass, attributeSet);
        }

        public void remove(FilterBypass filterBypass, int offset, int length) throws BadLocationException {
            if (doNotChangeDocument) return;
            super.remove(filterBypass, offset, length);
            postProcessDocumentChange(null, null);
        }

        /**
         * This method generically post processes changes to the ComboBox
         * editor's Document. The generic algorithm, regardless of the type of
         * change, is as follows:
         *
         * <ol>
         *   <li> save the prefix as the user has entered it
         *   <li> filter the combo box items against the prefix
         *   <li> update the text in the combo box editor with an autocomplete suggestion
         *   <li> try to show the popup, if possible
         * </ol>
         */
        private void postProcessDocumentChange(FilterBypass filterBypass, AttributeSet attributeSet) throws BadLocationException {
            // break out early if we're flagged to not post process the Document change
            if (doNotPostProcessDocumentChanges) return;

            updatePrefix();
            applyFilter(prefix);
            updateComboBox(filterBypass, attributeSet);
            togglePopup();
        }

        /**
         * This method will attempt to locate a reasonable autocomplete item
         * from all combo box items and select it. It will also populate the
         * combo box editor with the remaining text which matches the
         * autocomplete item and select it.
         */
        private void updateComboBox(FilterBypass filterBypass, AttributeSet attributeSet) throws BadLocationException {
            // make sure the prefix is not "" (in which case we skip the search)
            if (prefix.length() > 0) {
                // search the combobox model for a value that starts with our prefix
                for (int i = 0; i < comboBoxModel.getSize(); i++) {
                    final Object item = comboBoxModel.getElementAt(i);
                    final String itemString = item == null ? null : item.toString();

                    // if the user-specified prefix matches the itemString's prefix
                    // we have found an appropriate itemString to select
                    if (itemString != null && prefixMatcher.matches(itemString)) {
                        if (filterBypass != null) {
                            // either keep the user's prefix or replace it with the itemString's prefix
                            // depending on whether we correct the case
                            if (correctsCase) {
                                filterBypass.replace(0, prefix.length(), itemString, attributeSet);
                            } else {
                                final String itemSuffix = itemString.substring(prefix.length());
                                filterBypass.insertString (prefix.length(), itemSuffix, attributeSet);
                            }
                        }

                        // select the matched itemString
                        doNotChangeDocument = true;
                        try {
                            comboBox.setSelectedIndex(i);
                        } finally {
                            doNotChangeDocument = false;
                        }

                        // select the text after the prefix but before the end of the text
                        // (it represents the autocomplete text)
                        comboBoxEditor.select(prefix.length(), document.getLength());

                        return;
                    }
                }
            }

            // reset the selection since we couldn't find the prefix in the model
            // (this has the side-effect of scrolling the popup to the top)
            doNotChangeDocument = true;
            try {
                comboBox.setSelectedIndex(-1);
            } finally {
                doNotChangeDocument = false;
            }
        }
    }


    /**
     * This UI Delegate exists in order to decorate the regular ComboBoxUI
     * implementation with custom logic that bends it toward autocompletion
     * and filtering.
     *
     * It is very important that decoration be used rather than providing a
     * custom UI delegate because we wish to only alter the behaviour of the
     * JComboBox but NOT the look.
     *
     * @author James Lemieux
     */
    private class AutoCompleteComboBoxUI extends BasicComboBoxUI {

        /** The regular UI delegate of the JComboBox, which we are decorating. */
        private final BasicComboBoxUI delegate;

        /**
         * A listener which reacts to changes in the combo box model by
         * resizing the popup appropriately to accomodate the new data.
         */
        private final ListDataListener listDataHandler = new ListDataHandler();

        /**
         * We ensure the popup menu is sized correctly each time it is shown.
         * Namely, we respect the prototype display value of the combo box, if
         * it has one. Regardless of the width of the combo box, we attempt to
         * size the popup to accomodate the width of the prototype display value.
         */
        private final PopupMenuListener popupSizerHandler = new PopupSizer();

        /**
         * The MouseListener which is installed on the {@link #arrowButton} and
         * is responsible for clearing the filter and then showing / hiding the
         * {@link #popup}.
         */
        private ArrowButtonMouseListener arrowButtonMouseListener;

        /**
         * Control the selection behavior of the JComboBox when it is used in
         * the JTable DefaultCellEditor.
         */
        private boolean isTableCellEditor;

        /**
         * A handle to the JPopupMenu which functions as the popup of the
         * JComboBox.
         */
        private JPopupMenu popupMenu;

        /**
         * Build a UI delegate that decorates the given <code>delegate</code>
         * with autocompletion and filtering behaviour. The look of the
         * <code>delegate</code> UI should remain unchanged.
         *
         * @param delegate the delegate defining the look of this UI
         */
        public AutoCompleteComboBoxUI(BasicComboBoxUI delegate) {
            this.delegate = delegate;
        }

        public void installUI(JComponent c) {
            delegate.installUI(c);

            comboBox = (JComboBox) c;
            popupMenu = (JPopupMenu) delegate.getAccessibleChild(c, 0);
            popup = (ComboPopup) popupMenu;
            arrowButton = findArrowButton(comboBox);

            // is this combo box a table cell editor?
            isTableCellEditor = Boolean.TRUE.equals(c.getClientProperty("JComboBox.isTableCellEditor"));

            // if an arrow button was found, decorate the ComboPopup's MouseListener
            // with logic that unfilters the ComboBoxModel when the arrow button is pressed
            if (arrowButton != null) {
                arrowButton.removeMouseListener(popup.getMouseListener());
                arrowButtonMouseListener = new ArrowButtonMouseListener(popup.getMouseListener());
                arrowButton.addMouseListener(arrowButtonMouseListener);
            }

            // start listening for model changes (due to filtering) so we can resize the popup vertically
            comboBox.getModel().addListDataListener(listDataHandler);

            // calculate the popup's width according to the prototype value, if one exists
            popupMenu.addPopupMenuListener(popupSizerHandler);

            final ActionMap actionMap = comboBox.getActionMap();
            if (actionMap.get("selectNext") != null) {
                // install custom actions for the arrow keys in all non-Apple L&Fs
                actionMap.put("selectPrevious", new UpAction());
                actionMap.put("selectNext", new DownAction());

            } else if (actionMap.get("aquaSelectNext") != null) {
                // install custom actions for the arrow keys in the Apple Aqua L&F
                actionMap.put("aquaSelectPrevious", new UpAction());
                actionMap.put("aquaSelectNext", new DownAction());
            }
        }

        /**
         * A convenience method to search through the given component for the
         * JButton which toggles the popup up open and closed.
         */
        private JButton findArrowButton(JComponent c) {
            for (int i = 0; i < c.getComponentCount(); i++) {
                final Component comp = c.getComponent(i);
                if (comp instanceof JButton)
                    return (JButton) comp;
            }

            return null;
        }

        public void uninstallUI(JComponent c) {
            // reinstall the normal MouseListener for the arrowButton
            if (arrowButton != null) {
                arrowButton.removeMouseListener(arrowButtonMouseListener);
                arrowButton.addMouseListener(arrowButtonMouseListener.getDecorated());
            }

            // stop listening for model changes
            comboBox.getModel().removeListDataListener(listDataHandler);

            // stop listening for popup menu changes
            popupMenu.removePopupMenuListener(popupSizerHandler);

            // clear our member variable state
            comboBox = null;
            popupMenu = null;
            popup = null;
            arrowButton = null;

            // the delegate will uninstall our custom keyboard actions
            // so we don't worry about them here
            delegate.uninstallUI(c);
        }

        /**
         * Selects the next item in the list.  It won't change the selection if the
         * currently selected item is already the last item.
         */
        protected void selectNextPossibleValue() {
            selectPossibleValue((isTableCellEditor ? popup.getList().getSelectedIndex() : comboBox.getSelectedIndex()) + 1);
        }

        /**
         * Selects the previous item in the list.  It won't change the selection if the
         * currently selected item is already the first item.
         */
        protected void selectPreviousPossibleValue() {
            selectPossibleValue((isTableCellEditor ? popup.getList().getSelectedIndex() : comboBox.getSelectedIndex()) - 1);
        }

        /**
         * Select the item at the given index
         * @param index
         */
        private void selectPossibleValue(int index) {
            // wrap the index from past the start to the end of the list
            if (index == -2)
                index = comboBox.getModel().getSize()-1;

            // check if the index is within a valid range
            final boolean validIndex = index >= 0 && index < comboBox.getModel().getSize();

            // if the index isn't valid, select nothing
            if (!validIndex)
                index = -1;

            // adjust only the value in the comboBoxEditor, but leave the comboBoxModel unchanged
            doNotPostProcessDocumentChanges = true;
            try {
                // select the index
                if (isTableCellEditor) {
                    popup.getList().setSelectedIndex(index);
                    popup.getList().ensureIndexIsVisible(index);
                } else {
                    comboBox.setSelectedIndex(index);
                }

                // if the original index wasn't valid, we've cleared the selection
                // and must set the user's prefix into the editor
                if (!validIndex) {
                    comboBoxEditor.setText(prefix);

                    if (popupMenu.isShowing()) {
                        // clear the selected value from the popup's display
                        setPopupVisible(comboBox, false);
                        setPopupVisible(comboBox, true);
                    }
                }
            } finally {
                doNotPostProcessDocumentChanges = false;
            }

            // if the comboBoxEditor's values begins with the user's prefix, highlight the remainder of the value
            final String newSelection = comboBoxEditor.getText();
            if (prefixMatcher.matches(newSelection))
                comboBoxEditor.select(prefix.length(), newSelection.length());
        }

        /**
         * This class listens the to the ComboBoxModel redraws the popup if it
         * must grow or shrink to accomodate the latest list of items.
         */
        private class ListDataHandler implements ListDataListener {
            private int previousItemCount = -1;

            public void contentsChanged(ListDataEvent e) {
                final int newItemCount = comboBox.getItemCount();

                // if the size of the model didn't change, the popup size won't change
                if (previousItemCount == newItemCount)
                    return;

                final int maxPopupItemCount = comboBox.getMaximumRowCount();

                // if the popup is showing, check if it must be resized
                if (popupMenu.isShowing()) {
                    // if either the previous or new item count is less than the max,
                    // hide and show the popup to recalculate its new height
                    if (newItemCount < maxPopupItemCount || previousItemCount < maxPopupItemCount) {
                        setPopupVisible(comboBox, false);
                        setPopupVisible(comboBox, true);
                    }
                }

                previousItemCount = newItemCount;
            }
            public void intervalAdded(ListDataEvent e) { contentsChanged(e); }
            public void intervalRemoved(ListDataEvent e) { contentsChanged(e); }
        }

        /**
         * This class sizes the popup menu of the combo box immediately before
         * it is shown on the screen. In particular, it will adjust the width
         * of the popup to accomodate a prototype display value if the combo
         * box contains one.
         */
        private class PopupSizer implements PopupMenuListener {
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                // if the combo box does not contain a prototype display value, skip our sizing logic
                final Object prototypeValue = comboBox.getPrototypeDisplayValue();
                if (prototypeValue == null)
                    return;

                // attempt to extract the JScrollPane that scrolls the popup
                final JComponent popupComponent = (JComponent) e.getSource();
                if (popupComponent.getComponent(0) instanceof JScrollPane) {
                    final JScrollPane scroller = (JScrollPane) popupComponent.getComponent(0);

                    // fetch the existing preferred size of the scroller, and we'll check if it is large enough
                    final Dimension scrollerSize = scroller.getPreferredSize();

                    // calculates the preferred size of the renderer's component for the prototype value
                    final Dimension prototypeSize = getPrototypeSize(prototypeValue);

                    // add to the preferred width, the width of the vertical scrollbar, when it is visible
                    prototypeSize.width += scroller.getVerticalScrollBar().getPreferredSize().width;

                    // adjust the preferred width of the scroller, if necessary
                    if (prototypeSize.width > scrollerSize.width) {
                        scrollerSize.width = prototypeSize.width;

                        // set the new size of the scroller
                        scroller.setMaximumSize(scrollerSize);
                        scroller.setPreferredSize(scrollerSize);
                        scroller.setMinimumSize(scrollerSize);
                    }
                }
            }

            private Dimension getPrototypeSize(Object prototypeValue) {
                // get the renderer responsible for drawing the prototype value
                ListCellRenderer renderer = comboBox.getRenderer();
                if (renderer == null)
                    renderer = new DefaultListCellRenderer();

                // get the component from the renderer
                final Component comp = renderer.getListCellRendererComponent(popup.getList(), prototypeValue, -1, false, false);

                // determine the preferred size of the component
                currentValuePane.add(comp);
                comp.setFont(comboBox.getFont());
                Dimension d = comp.getPreferredSize();
                currentValuePane.remove(comp);
                return d;
            }

            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
            public void popupMenuCanceled(PopupMenuEvent e) {}
        }

        /**
         * When the user clicks on the arrow button, we always clear the
         * filtering from the model to emulate Firefox style autocompletion.
         */
        private class ArrowButtonMouseListener implements MouseListener {
            private final MouseListener decorated;

            public ArrowButtonMouseListener(MouseListener decorated) {
                this.decorated = decorated;
            }

            public void mousePressed(MouseEvent e) {
                // clear the filter if we're about to hide or show the popup
                // by clicking on the arrow button (this is EXPLICITLY different
                // than using the up/down arrow keys to show the popup
                applyFilter("");
                decorated.mousePressed(e);
            }
            public MouseListener getDecorated() { return decorated; }
            public void mouseClicked(MouseEvent e) { decorated.mouseClicked(e); }
            public void mouseReleased(MouseEvent e) { decorated.mouseReleased(e); }
            public void mouseEntered(MouseEvent e) { decorated.mouseEntered(e); }
            public void mouseExited(MouseEvent e) { decorated.mouseExited(e); }
        }

        /**
         * The action invoked by hitting the down arrow key.
         */
        private class DownAction extends AbstractAction {
            public void actionPerformed(ActionEvent e) {
                if (comboBox.isShowing()) {
                    if (comboBox.isPopupVisible()) {
                        selectNextPossibleValue();
                    } else {
                        applyFilter(prefix);
                        comboBox.setPopupVisible(true);
                    }
                }
            }
        }

        /**
         * The action invoked by hitting the up arrow key.
         */
        private class UpAction extends AbstractAction {
            public void actionPerformed(ActionEvent e) {
                if (comboBox.isShowing()) {
                    if (comboBox.isPopupVisible()) {
                        selectPreviousPossibleValue();
                    } else {
                        applyFilter(prefix);
                        comboBox.setPopupVisible(true);
                    }
                }
            }
        }

        public void addEditor() { delegate.addEditor(); }
        public void removeEditor() { delegate.removeEditor(); }
        public void configureArrowButton() { delegate.configureArrowButton(); }
        public void unconfigureArrowButton() { delegate.unconfigureArrowButton(); }
        public boolean isPopupVisible(JComboBox c) { return delegate.isPopupVisible(c); }
        public void setPopupVisible(JComboBox c, boolean v) { delegate.setPopupVisible(c, v); }
        public boolean isFocusTraversable(JComboBox c) { return delegate.isFocusTraversable(c); }
        public void paint(Graphics g, JComponent c) { delegate.paint(g, c); }
        public Dimension getPreferredSize(JComponent c) { return delegate.getPreferredSize(c); }
        public Dimension getMinimumSize(JComponent c) { return delegate.getMinimumSize(c); }
        public Dimension getMaximumSize(JComponent c) { return delegate.getMaximumSize(c); }
        public int getAccessibleChildrenCount(JComponent c) { return delegate.getAccessibleChildrenCount(c); }
        public Accessible getAccessibleChild(JComponent c, int i) { return delegate.getAccessibleChild(c, i); }
        public void paintCurrentValue(Graphics g, Rectangle bounds, boolean hasFocus) { delegate.paintCurrentValue(g, bounds, hasFocus); }
        public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) { delegate.paintCurrentValueBackground(g, bounds, hasFocus); }
        public void update(Graphics g, JComponent c) { delegate.update(g, c); }
        public boolean contains(JComponent c, int x, int y) { return delegate.contains(c, x, y); }
    }

    /**
     * Watch for a change of the ComboBoxUI and report it as a violation.
     */
    private class UIWatcher implements PropertyChangeListener {
        public void propertyChange(PropertyChangeEvent evt) {
            throwIllegalStateException("The ComboBoxUI cannot be changed. It was changed to: " + evt.getNewValue());
        }
    }

    /**
     * Watch for a change of the ComboBoxModel and report it as a violation.
     */
    private class ModelWatcher implements PropertyChangeListener {
        public void propertyChange(PropertyChangeEvent evt) {
            throwIllegalStateException("The ComboBoxModel cannot be changed. It was changed to: " + evt.getNewValue());
        }
    }

    /**
     * Watch the Document behind the editor component in case it changes. If a
     * new Document is swapped in, uninstall our DocumentFilter from the old
     * Document and install it on the new.
     */
    private class DocumentWatcher implements PropertyChangeListener {
        public void propertyChange(PropertyChangeEvent evt) {
            final Document newDocument = (Document) evt.getNewValue();

            if (!(newDocument instanceof AbstractDocument))
                throwIllegalStateException("The Document behind the JTextField was changed to no longer be an AbstractDocument. It was changed to: " + newDocument);

            // remove our DocumentFilter from the old document
            document.setDocumentFilter(null);

            // update the document we track internally
            document = (AbstractDocument) newDocument;

            // add our DocumentFilter to the new Document
            document.setDocumentFilter(documentFilter);
        }
    }
}