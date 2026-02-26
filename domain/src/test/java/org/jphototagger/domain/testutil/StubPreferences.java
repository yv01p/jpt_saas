package org.jphototagger.domain.testutil;

import java.awt.Component;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import org.jphototagger.api.preferences.Preferences;
import org.jphototagger.api.preferences.PreferencesHints;
import org.openide.util.lookup.ServiceProvider;

/**
 * No-op Preferences implementation for headless test environments where the
 * full application context is not available.
 */
@ServiceProvider(service = Preferences.class)
public class StubPreferences implements Preferences {

    @Override public String getString(String key) { return ""; }
    @Override public void setString(String key, String value) {}
    @Override public void setBoolean(String key, boolean value) {}
    @Override public boolean getBoolean(String key) { return false; }
    @Override public boolean getBoolean(String key, boolean valueIfNotDefined) { return valueIfNotDefined; }
    @Override public void setStringCollection(String key, Collection<? extends String> stringCollection) {}
    @Override public int getInt(String key) { return 0; }
    @Override public void setInt(String key, int value) {}
    @Override public void setTree(String key, JTree tree) {}
    @Override public void setScrollPane(String key, JScrollPane scrollPane) {}
    @Override public void setToggleButton(String key, JToggleButton button) {}
    @Override public void setTabbedPane(String key, JTabbedPane pane, PreferencesHints hints) {}
    @Override public void setComponent(Component component, PreferencesHints hints) {}
    @Override public void setSelectedIndex(String key, JComboBox<?> comboBox) {}
    @Override public void setSelectedIndices(String key, JList<?> list) {}
    @Override public boolean containsKey(String key) { return false; }
    @Override public boolean containsLocationKey(String key) { return false; }
    @Override public boolean containsSizeKey(String key) { return false; }
    @Override public void removeKey(String key) {}
    @Override public void removeStringCollection(String key) {}
    @Override public void setSize(String key, Component component) {}
    @Override public void applySize(String key, Component component) {}
    @Override public void applyTreeSettings(String key, JTree tree) {}
    @Override public void applyScrollPaneSettings(String key, JScrollPane scrollPane) {}
    @Override public void applyToggleButtonSettings(String key, JToggleButton button) {}
    @Override public void applyTabbedPaneSettings(String key, JTabbedPane pane, PreferencesHints hints) {}
    @Override public void applyComponentSettings(Component component, PreferencesHints hints) {}
    @Override public void applySelectedIndex(String key, JComboBox<?> comboBox) {}
    @Override public void applySelectedIndices(String key, JList<?> list) {}
    @Override public void setLocation(String key, Component component) {}
    @Override public void applyLocation(String key, Component component) {}
    @Override public List<String> getStringCollection(String key) { return Collections.emptyList(); }
    @Override public Set<String> keys() { return Collections.emptySet(); }
}
