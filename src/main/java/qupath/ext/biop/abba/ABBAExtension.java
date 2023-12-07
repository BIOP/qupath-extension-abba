package qupath.ext.biop.abba;

import org.controlsfx.control.action.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.actions.ActionTools;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.tools.MenuTools;

import java.io.InputStream;
import java.util.LinkedHashMap;

/**
 * Install ABBA extension as an extension.
 * <p>
 * Installs ABBA extension into QuPath, adding some metadata and adds the necessary global variables to QuPath's Preferences
 *
 * @author Nicolas Chiaruttini
 */
public class ABBAExtension implements QuPathExtension, GitHubProject {
    private final static Logger logger = LoggerFactory.getLogger(ABBAExtension.class);

    private static final LinkedHashMap<String, String> SCRIPTS = new LinkedHashMap<>() {{
        put("Code snippets", "scripts/Code_snippets.groovy");
        put("Compute cell centroid atlas coordinates", "scripts/Compute_cell_centroid_atlas_coordinates.groovy");
    }};

    @Override
    public GitHubRepo getRepository() {
        return GitHubRepo.create("QuPath ABBA Extension", "biop", "qupath-extension-abba");
    }
    private static boolean alreadyInstalled = false;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (alreadyInstalled)
            return;


        var actionLoadAtlasRois = ActionTools.createAction(new LoadAtlasRoisToQuPathCommand(qupath), "Load Atlas Annotations into Open Image");

        MenuTools.addMenuItems(qupath.getMenu("Extensions", false),
                MenuTools.createMenu("ABBA",actionLoadAtlasRois)
        );

        SCRIPTS.entrySet().forEach(entry -> {
            String name = entry.getValue();
            String command = entry.getKey();
            try (InputStream stream = ABBAExtension.class.getClassLoader().getResourceAsStream(name)) {
                String script = new String(stream.readAllBytes(), "UTF-8");
                if (script != null) {
                    MenuTools.addMenuItems(
                            qupath.getMenu("Extensions>ABBA>Scripts", true),
                            new Action(command, e -> openScript(qupath, script)));
                }
            } catch (Exception e) {
                logger.error(e.getLocalizedMessage(), e);
            }
        });
        alreadyInstalled = true;

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

    private static void openScript(QuPathGUI qupath, String script) {
        var editor = qupath.getScriptEditor();
        if (editor == null) {
            logger.error("No script editor is available!");
            return;
        }
        qupath.getScriptEditor().showScript("Cellpose detection", script);
    }
}