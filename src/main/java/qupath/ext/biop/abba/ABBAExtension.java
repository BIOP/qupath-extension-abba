package qupath.ext.biop.abba;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;

/**
 * Install Warpy as an extension.
 * <p>
 * Installs Warpy into QuPath, adding some metadata and adds the necessary global variables to QuPath's Preferences
 *
 * @author Nicolas Chiaruttini
 */
public class ABBAExtension implements QuPathExtension, GitHubProject {
    private final static Logger logger = LoggerFactory.getLogger(ABBAExtension.class);


    @Override
    public GitHubRepo getRepository() {
        return GitHubRepo.create("QuPath ABBA Extension", "biop", "qupath-extension-abba");
    }

    @Override
    public void installExtension(QuPathGUI qupath) {

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