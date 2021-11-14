package qupath.ext.biop.abba;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.Version;
import qupath.lib.gui.ActionTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.tools.MenuTools;

/**
 * Install ABBA extension as an extension.
 * <p>
 * Installs ABBA extension into QuPath, adding some metadata and adds the necessary global variables to QuPath's Preferences
 *
 * @author Nicolas Chiaruttini
 */
public class ABBAExtension implements QuPathExtension, GitHubProject {
    private final static Logger logger = LoggerFactory.getLogger(ABBAExtension.class);

    @Override
    public GitHubRepo getRepository() {
        return GitHubRepo.create("QuPath ABBA Extension", "biop", "qupath-extension-abba");
    }

    private static boolean alreadyInstalled = false;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (alreadyInstalled)
            return;
        alreadyInstalled = true;
        var actionLoadAtlasRois = ActionTools.createAction(new LoadAtlasRoisToQuPathCommand(qupath), "Load Atlas Annotations into Open Image");

        MenuTools.addMenuItems(qupath.getMenu("Extensions", false),
                MenuTools.createMenu("ABBA",actionLoadAtlasRois)
        );
    }

    @Override
    public String getName() {
        return "ABBA extension";
    }

    @Override
    public String getDescription() {
        return "An extension for QuPath that allows to use Aligning Big Brains and Atlases plugin";
    }

    @Override
    public Version getQuPathVersion() {
        return QuPathExtension.super.getQuPathVersion();
    }

    @Override
    public Version getVersion() {
        return Version.parse("0.1.0-SNAPSHOT");
    }
}