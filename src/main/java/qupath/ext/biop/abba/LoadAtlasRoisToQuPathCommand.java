package qupath.ext.biop.abba;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.abba.struct.AtlasHelper;
import qupath.ext.biop.abba.struct.AtlasOntology;
import qupath.lib.gui.QuPathGUI;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;
import qupath.lib.plugins.workflow.WorkflowStep;
import qupath.lib.scripting.QP;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class LoadAtlasRoisToQuPathCommand implements Runnable {

    final static Logger logger = LoggerFactory.getLogger(LoadAtlasRoisToQuPathCommand.class);

    private static QuPathGUI qupath;

    private boolean splitLeftRight;
    private boolean doRun;

    public LoadAtlasRoisToQuPathCommand( final QuPathGUI qupath) {
        this.qupath = qupath;
    }

    public void run() {

        String splitMode =
                Dialogs.showChoiceDialog("Load Brain RoiSets into Image",
                        "This will load any RoiSets Exported using the ABBA tool onto the current image.\nContinue?", new String[]{"Split Left and Right Regions", "Do not split"}, "Do not split");

        switch (splitMode) {
            case "Do not split" :
                splitLeftRight = false;
                doRun = true;
                break;
            case "Split Left and Right Regions" :
                splitLeftRight = true;
                doRun = true;
                break;
            default:
                // null returned -> cancelled
                doRun = false;
                return;
        }
        if (doRun) {
            ImageData<BufferedImage> imageData = qupath.getImageData();
            List<String> ontologyFiles = AtlasTools.getAvailableAtlasOntologyFiles();
            if (ontologyFiles == null || ontologyFiles.isEmpty()) {
                Dialogs.showErrorMessage("No atlas ontology found.", "You first need to export your registration from Fiji's ABBA plugin.");
                logger.error("No atlas ontology found."); // TODO : show an error message for the user
                return;
            }

            // Get atlas ontology
            String ontologyName;
            String ontologyFile;
            String ontologySuffix = "-Ontology.json";
            if (ontologyFiles.size() > 1) {
                ontologyName =
                        Dialogs.showChoiceDialog("Atlas ontologies",
                                "Please select the preferred atlas ontology used for registration.",
                                ontologyFiles.stream()
                                        .map(of -> of.substring(0, of.length() - ontologySuffix.length()))
                                        .toList(),
                                null);
                ontologyFile = ontologyName+ontologySuffix;
            } else {
                ontologyFile = ontologyFiles.get(0);
                ontologyName = ontologyFile.substring(0, ontologyFile.length() - ontologySuffix.length());
            }

            Path ontologyPath = Paths.get(QP.buildPathInProject(ontologyFile)).toAbsolutePath();
            AtlasOntology ontology = AtlasHelper.openOntologyFromJsonFile(ontologyPath.toString());

            // Get naming possibilities
            String namingProperty =
                    Dialogs.showChoiceDialog("Regions names",
                            "Please select the property for naming the imported regions.",
                            AtlasTools.getNamingProperties(ontology).toArray(new String[0]),
                            "ID");

            ontology.setNamingProperty(namingProperty);

            // Now we have all we need, the name whether to split left and right
            PathObject rootAnnotation = AtlasTools.loadWarpedAtlasAnnotations(ontology, imageData, ontologyName, splitLeftRight, true);

            if (rootAnnotation == null) {
                Dialogs.showErrorMessage("No atlas registration found.",
                        "Can't find the registration corresponding to the atlas ontology of the project."+
                                "You first need to export your registration from Fiji's ABBA plugin.");
                logger.error("No atlas registartion found."); // TODO : show an error message for the user
                return;
            }

            // Add a step to the workflow
            String method = AtlasTools.class.getName()+".loadWarpedAtlasAnnotations(getCurrentImageData(), \""+ontologyName+"\", \""+namingProperty+"\", "+splitLeftRight+", true);";
            WorkflowStep newStep = new DefaultScriptableWorkflowStep("Load Brain RoiSets into Image", method);
            imageData.getHistoryWorkflow().addStep(newStep);
        }
    }

}