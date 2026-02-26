package org.jphototagger.lib.io;

import java.io.File;
import javax.swing.tree.DefaultMutableTreeNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * @author Elmar Baumann
 */
public class TreeFileSystemDirectoriesTest {

    @Test
    public void testUpdateFilesAfterRenamingInTreeModel() {
        /*
        Before Renaming:
              1
              |
              +-2
              | |
              | +-3
              |
              +-3
        */
        DefaultMutableTreeNode ancestor = new DefaultMutableTreeNode(new File("/2"));
        DefaultMutableTreeNode firstChild = new DefaultMutableTreeNode(new File("/1/2"));
        DefaultMutableTreeNode firstChildsFirstChild = new DefaultMutableTreeNode(new File("/1/2/3"));
        DefaultMutableTreeNode secondChild = new DefaultMutableTreeNode(new File("/1/3"));

        ancestor.add(firstChild);
        firstChild.add(firstChildsFirstChild);
        ancestor.add(secondChild);

        TreeFileSystemDirectories.updateFilesAfterRenamingInTreeModel(ancestor, "/1");

        Assertions.assertEquals("/2/2", ((File) firstChild.getUserObject()).getAbsolutePath());
        Assertions.assertEquals("/2/2/3", ((File) firstChildsFirstChild.getUserObject()).getAbsolutePath());
        Assertions.assertEquals("/2/3", ((File) secondChild.getUserObject()).getAbsolutePath());
    }
}
