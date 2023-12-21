/**
 * REQUIREMENTS
 * ============
 * You need to:
 *  - have the QuPath ABBA Extension installed (https://github.com/BIOP/qupath-extension-abba)
 *  - have an opened image which has been registered with ABBA (https://biop.github.io/ijp-imagetoatlas/)
 *  - have exported the registration result of the image in ABBA (in Fiji)
 *  - have some cells already detected on this opened image
 *
 * TODO : Display a warning message if several atlases are found (Allen v3 and Allen v3.1)	
 *
 * This script then computes the centroid coordinates of each detection within the atlas
 * and adds these coordinates onto the measurement list.
 * Measurements names: "Atlas_X", "Atlas_Y", "Atlas_Z"
 */

def pixelToAtlasTransform = 
    AtlasTools
    .getAtlasToPixelTransform(getCurrentImageData())
    .inverse() // pixel to atlas = inverse of atlas to pixel

getDetectionObjects().forEach(detection -> {
    RealPoint atlasCoordinates = new RealPoint(3);
    MeasurementList ml = detection.getMeasurementList();
    atlasCoordinates.setPosition([detection.getROI().getCentroidX(),detection.getROI().getCentroidY(),0] as double[]);
    pixelToAtlasTransform.apply(atlasCoordinates, atlasCoordinates);
    ml.putMeasurement("Atlas_X", atlasCoordinates.getDoublePosition(0) )
    ml.putMeasurement("Atlas_Y", atlasCoordinates.getDoublePosition(1) )
    ml.putMeasurement("Atlas_Z", atlasCoordinates.getDoublePosition(2) )
})

import qupath.ext.warpy.Warpy
import net.imglib2.RealPoint
import qupath.lib.measurements.MeasurementList
import qupath.ext.biop.abba.AtlasTools

import static qupath.lib.gui.scripting.QPEx.* // For intellij editor autocompletion
