package qupath.ext.biop.abba;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.images.ImageData;

public class LoadAtlasRoisToQuPathCommand implements Runnable {

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
            ImageData imageData = qupath.getImageData();
            // TODO : Find atlas name
            AtlasTools.loadWarpedAtlasAnnotations(imageData, "Adult Mouse Brain - Allen Brain Atlas V3", splitLeftRight);
            System.out.println("Import DONE");
        }
    }

}