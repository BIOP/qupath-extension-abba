package qupath.ext.biop.abba;

import ij.gui.Roi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.abba.struct.AtlasHelper;
import qupath.ext.biop.abba.struct.AtlasNode;
import qupath.ext.biop.abba.struct.AtlasOntology;
import qupath.imagej.tools.IJTools;
import qupath.lib.common.ColorTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.RotatedImageServer;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.measurements.MeasurementListFactory;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.PathObjects;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class AtlasTools {

    final static Logger logger = LoggerFactory.getLogger(AtlasTools.class);

    private static QuPathGUI qupath = QuPathGUI.getInstance();

    private static String title = "Load ABBA RoiSets from current QuPath project";

    static private PathObject createAnnotationHierarchy(List<PathObject> annotations) {

        // Map the ID of the annotation to ease finding parents
        Map<Integer, PathObject> mappedAnnotations =
                annotations
                        .stream()
                        .collect(
                                Collectors.toMap(e -> (int) (e.getMeasurementList().getMeasurementValue("ID")), e -> e)
                        );

        AtomicReference<PathObject> rootReference = new AtomicReference<>();

        mappedAnnotations.forEach((id, annotation) -> {
            PathObject parent = mappedAnnotations.get((int) annotation.getMeasurementList().getMeasurementValue("Parent ID"));
            if (parent != null) {
                parent.addPathObject(annotation);
            } else {
                // Found the root Path Object
                rootReference.set(annotation);
            }
        });

        // Return just the root annotation from the atlas
        return rootReference.get();
    }

    static PathObject getWarpedAtlasRegions(ImageData imageData, String atlasName, boolean splitLeftRight) {

        List<PathObject> annotations = getFlattenedWarpedAtlasRegions(imageData, atlasName, splitLeftRight); // TODO
        if (splitLeftRight) {
            List<PathObject> annotationsLeft = annotations
                    .stream()
                    .filter(po -> po.getPathClass().isDerivedFrom(QP.getPathClass("Left")))
                    .collect(Collectors.toList());

            List<PathObject> annotationsRight = annotations
                    .stream()
                    .filter(po -> po.getPathClass().isDerivedFrom(QP.getPathClass("Right")))
                    .collect(Collectors.toList());

            PathObject rootLeft = createAnnotationHierarchy(annotationsLeft);
            PathObject rootRight = createAnnotationHierarchy(annotationsRight);
            ROI rootFused = RoiTools.combineROIs(rootLeft.getROI(), rootRight.getROI(), RoiTools.CombineOp.ADD);
            PathObject rootObject = PathObjects.createAnnotationObject(rootFused);
            rootObject.setName("Root");
            rootObject.addPathObject(rootLeft);
            rootObject.addPathObject(rootRight);
            return rootObject; // TODO
        } else {
            return createAnnotationHierarchy(annotations);
        }

    }

    public static List<PathObject> getFlattenedWarpedAtlasRegions(ImageData imageData, String atlasName, boolean splitLeftRight) {
        Project project = qupath.getProject();

        // Get the project folder and get the ontology
        Path ontologyPath = Paths.get(Projects.getBaseDirectory(project).getAbsolutePath(), atlasName+"-Ontology.json");
        AtlasOntology ontology = AtlasHelper.openOntologyFromJsonFile(ontologyPath.toString());

        // Loop through each ImageEntry
        ProjectImageEntry entry = project.getEntry(imageData);

        Path roisetPath = Paths.get(entry.getEntryPath().toString(), "ABBA-Roiset-"+atlasName+".zip");
        if (!Files.exists(roisetPath)) {
            logger.info("No RoiSets found in {}", roisetPath);
            return null;
        }

        // Get all the ROIs and add them as PathAnnotations
        List<Roi> rois = RoiSetLoader.openRoiSet(roisetPath.toAbsolutePath().toFile());
        logger.info("Loading {} Atlas Regions for {}", rois.size(), entry.getImageName());

        Roi left = rois.get(rois.size() - 2);
        Roi right = rois.get(rois.size() - 1);

        rois.remove(left);
        rois.remove(right);

        // Rotation for rotated servers
        ImageServer<?> server = imageData.getServer();

        AffineTransform transform = null;

        if (server instanceof RotatedImageServer) {
            // The roi will need to be transformed before being imported
            // First : get the rotation
            RotatedImageServer ris = (RotatedImageServer) server;
            switch (ris.getRotation()) {
                case ROTATE_NONE: // No rotation.
                    break;
                case ROTATE_90: // Rotate 90 degrees clockwise.
                    transform = AffineTransform.getRotateInstance(Math.PI/2.0);
                    transform.translate(0, -server.getWidth());
                    break;
                case ROTATE_180: // Rotate 180 degrees.
                    transform = AffineTransform.getRotateInstance(Math.PI);
                    transform.translate(-server.getWidth(), -server.getHeight());
                    break;
                case ROTATE_270: // Rotate 270 degrees
                    transform = AffineTransform.getRotateInstance(Math.PI*3.0/2.0);
                    transform.translate(-server.getHeight(), 0);
                    break;
                default:
                    System.err.println("Unknow rotation for rotated image server: "+ris.getRotation());
            }
        }

        AffineTransform finalTransform = transform;

        List<PathObject> annotations = rois.stream().map(roi -> {
            // Create the PathObject

            //PathObject object = IJTools.convertToAnnotation( imp, imageData.getServer(), roi, 1, null );
            PathObject object = PathObjects.createAnnotationObject(IJTools.convertToROI(roi, 0, 0, 1, null));

            // Handles rotated image server
            if (finalTransform !=null) {
                object = PathObjectTools.transformObject(object, finalTransform, true);
            }

            // Add metadata to object as acquired from the Ontology
            int object_id = Integer.parseInt(roi.getName());
            // Get associated information
            AtlasNode node = ontology.getNodeFromId(object_id);
            String name = node.data().get(ontology.getNamingProperty());
            System.out.println("node:"+node.getId()+":"+name);
            object.setName(name);
            object.getMeasurementList().putMeasurement("ID", node.getId());
            if (node.parent()!=null) {
                object.getMeasurementList().putMeasurement("Parent ID", node.parent().getId());
            }
            object.getMeasurementList().putMeasurement("Side", 0);
            object.setPathClass(QP.getPathClass(name));
            object.setLocked(true);
            int[] rgba = node.getColor();
            int color = ColorTools.packRGB(rgba[0], rgba[1], rgba[2]);
            object.setColorRGB(color);
            return object;
        }).collect(Collectors.toList());

        if (splitLeftRight) {
            ROI leftROI = IJTools.convertToROI(left, 0, 0, 1, null);
            ROI rightROI = IJTools.convertToROI(right, 0, 0, 1, null);
            List<PathObject> splitObjects = new ArrayList<>();
            for (PathObject annotation : annotations) {
                ROI shapeLeft = RoiTools.combineROIs(leftROI, annotation.getROI(), RoiTools.CombineOp.INTERSECT);
                if (!shapeLeft.isEmpty()) {
                    PathObject objectLeft = PathObjects.createAnnotationObject(shapeLeft, annotation.getPathClass(), duplicateMeasurements(annotation.getMeasurementList()));
                    objectLeft.setName(annotation.getName());
                    objectLeft.setPathClass(QP.getDerivedPathClass(QP.getPathClass("Left"), annotation.getPathClass().getName()));
                    objectLeft.setColorRGB(annotation.getColorRGB());
                    objectLeft.setLocked(true);
                    splitObjects.add(objectLeft);
                }

                ROI shapeRight = RoiTools.combineROIs(rightROI, annotation.getROI(), RoiTools.CombineOp.INTERSECT);
                if (!shapeRight.isEmpty()) {
                    PathObject objectRight = PathObjects.createAnnotationObject(shapeRight, annotation.getPathClass(), duplicateMeasurements(annotation.getMeasurementList()));
                    objectRight.setName(annotation.getName());
                    objectRight.setPathClass(QP.getDerivedPathClass(QP.getPathClass("Right"), annotation.getPathClass().getName()));
                    objectRight.setColorRGB(annotation.getColorRGB());
                    objectRight.setLocked(true);
                    splitObjects.add(objectRight);
                }

            }
            return splitObjects;
        } else {
            return annotations;
        }
    }

    public static void loadWarpedAtlasAnnotations(ImageData imageData, String atlasName, boolean splitLeftRight) {
        imageData.getHierarchy().addPathObject(getWarpedAtlasRegions(imageData, atlasName, splitLeftRight));
        imageData.getHierarchy().fireHierarchyChangedEvent(AtlasTools.class);
    }

    private static MeasurementList duplicateMeasurements(MeasurementList measurements) {
        MeasurementList list = MeasurementListFactory.createMeasurementList(measurements.size(), MeasurementList.MeasurementListType.GENERAL);

        for (int i = 0; i < measurements.size(); i++) {
            String name = measurements.getMeasurementName(i);
            double value = measurements.getMeasurementValue(i);
            list.addMeasurement(name, value);
        }
        return list;
    }

}
