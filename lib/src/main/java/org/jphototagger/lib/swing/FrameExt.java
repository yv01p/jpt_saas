package org.jphototagger.lib.swing;

import javax.swing.JFrame;
import org.jphototagger.resources.UiFactory;

/**
 * Frame to use in JPhotoTagger instead of {@link JFrame} to achieve a unique
 * Behaviour and Look and Feel.
 *
 * @author Elmar Baumann
 */
public class FrameExt extends JFrame {

    private static final long serialVersionUID = 1L;

    public FrameExt() {
        init();
    }

    private void init() {
        UiFactory.configure(this);
    }
}
