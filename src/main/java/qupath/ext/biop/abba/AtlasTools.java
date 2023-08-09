package qupath.ext.biop.abba;

import ij.gui.Roi;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.InvertibleRealTransform;
import net.imglib2.realtransform.InvertibleRealTransformSequence;
import net.imglib2.realtransform.RealTransform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.abba.struct.AtlasHelper;
import qupath.ext.biop.abba.struct.AtlasNode;
import qupath.ext.biop.abba.struct.AtlasOntology;
import qupath.ext.biop.warpy.Warpy;
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
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AtlasTools {

    final static Logger logger = LoggerFactory.getLogger(AtlasTools.class);

    private static final QuPathGUI qupath = QuPathGUI.getInstance();

    static private PathObject createAnnotationHierarchy(List<PathObject> annotations) {

        // Map the ID of the annotation to ease finding parents
        Map<Integer, PathObject> mappedAnnotations =
                annotations
                        .stream()
                        .collect(
                                Collectors.toMap(e -> (int) (e.getMeasurementList().get("ID")), e -> e)
                        );

        AtomicReference<PathObject> rootReference = new AtomicReference<>();

        mappedAnnotations.forEach((id, annotation) -> {
            PathObject parent = mappedAnnotations.get((int) annotation.getMeasurementList().get("Parent ID"));
            if (parent != null) {
                parent.addChildObject(annotation);
            } else {
                // System.out.println("No parent, id = "+id);
                // Found the root Path Object
                rootReference.set(annotation);
            }
        });

        // Return just the root annotation from the atlas
        return rootReference.get();
    }

    static PathObject getWarpedAtlasRegions(AtlasOntology ontology, ImageData<BufferedImage> imageData, String atlasName, boolean splitLeftRight) {

        List<PathObject> annotations = getFlattenedWarpedAtlasRegions(ontology, imageData, atlasName, splitLeftRight);

        if (annotations == null) return null;

        if (splitLeftRight) {
            assert annotations != null;
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
            ROI rootFused;
            PathObject rootObject;
            if ((rootLeft!=null)&&(rootRight!=null)) {
                rootFused = RoiTools.combineROIs(rootLeft.getROI(), rootRight.getROI(), RoiTools.CombineOp.ADD);
            } else if (rootLeft==null) {
                rootFused = RoiTools.combineROIs(ROIs.createEmptyROI(), rootRight.getROI(), RoiTools.CombineOp.ADD);
            } else {
                assert rootRight==null;
                rootFused = RoiTools.combineROIs(rootLeft.getROI(), ROIs.createEmptyROI(), RoiTools.CombineOp.ADD);
            }
            rootObject = PathObjects.createAnnotationObject(rootFused);
            rootObject.setName("Root");
            if (rootLeft!=null) {
                rootObject.addChildObject(rootLeft);
            }
            if (rootRight!=null) {
                rootObject.addChildObject(rootRight);
            }
            return rootObject; // TODO
        } else {
            assert annotations != null;
            return createAnnotationHierarchy(annotations);
        }

    }

    public static List<PathObject> getFlattenedWarpedAtlasRegions(AtlasOntology ontology, ImageData<BufferedImage> imageData, String atlasName, boolean splitLeftRight) {
        Project<BufferedImage> project = qupath.getProject();

        // Loop through each ImageEntry
        ProjectImageEntry<BufferedImage> entry = project.getEntry(imageData);

        Path roisetPath = Paths.get(entry.getEntryPath().toString(), "ABBA-RoiSet-"+atlasName+".zip");
        if (!Files.exists(roisetPath)) {
            logger.info("No RoiSets found in {}", roisetPath);
            return null;
        }

        // Get all the ROIs and add them as PathAnnotations
        List<Roi> rois = RoiSetLoader.openRoiSet(roisetPath.toAbsolutePath().toFile());
        logger.info("Loading {} Atlas Regions for {}", rois.size(), entry.getImageName());

        Roi right = null;
        Roi left = null;

        for (Roi roi:rois) {
            if (roi.getName().equals("Left")) {
                left = roi;
            }
            if (roi.getName().equals("Right")) {
                right = roi;
            }
        }

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
                    System.err.println("Unknown rotation for rotated image server: "+ris.getRotation());
            }
        }

        AffineTransform finalTransform = transform;

        List<PathObject> annotations = rois.stream().map(roi -> {
            // Create PathObject

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
            object.setName(name);
            object.getMeasurementList().put("ID", node.getId());
            if (node.parent()!=null) {
                //System.out.println("Parent ID = "+node.parent().getId());
                object.getMeasurementList().put("Parent ID", node.parent().getId());
            } else {
                //System.out.println("Node without parent; id = "+node.getId());
            }
            object.getMeasurementList().put("Side", 0);
            object.setPathClass(QP.getPathClass(name));
            object.setLocked(true);
            int[] rgba = node.getColor();
            int color = ColorTools.packRGB(rgba[0], rgba[1], rgba[2]);
            object.setColor(color);
            return object;
        }).collect(Collectors.toList());

        if (splitLeftRight) {
            ROI leftROI = null;
            ROI rightROI = null;
            if (left!=null) {
                leftROI = IJTools.convertToROI(left, 0, 0, 1, null);
                // Handles rotated image server
                if (finalTransform !=null) {
                    PathObject leftObject = PathObjects.createAnnotationObject(leftROI);
                    leftROI = PathObjectTools.transformObject(leftObject, finalTransform, true).getROI();
                }
            }
            if (right!=null) {
                rightROI = IJTools.convertToROI(right, 0, 0, 1, null);
                // Handles rotated image server
                if (finalTransform !=null) {
                    PathObject rightObject = PathObjects.createAnnotationObject(rightROI);
                    rightROI = PathObjectTools.transformObject(rightObject, finalTransform, true).getROI();
                }
            }
            List<PathObject> splitObjects = new ArrayList<>();
            for (PathObject annotation : annotations) {

                if (leftROI!=null) {
                    ROI shapeLeft = RoiTools.combineROIs(leftROI, annotation.getROI(), RoiTools.CombineOp.INTERSECT);
                    if (!shapeLeft.isEmpty()) {
                        PathObject objectLeft = PathObjects.createAnnotationObject(shapeLeft, annotation.getPathClass(), duplicateMeasurements(annotation.getMeasurementList()));
                        objectLeft.setName(annotation.getName());
                        objectLeft.setPathClass(QP.getDerivedPathClass(QP.getPathClass("Left"), annotation.getPathClass().getName()));
                        objectLeft.setColor(annotation.getColor());
                        objectLeft.setLocked(true);
                        splitObjects.add(objectLeft);
                    }
                }

                if (rightROI!=null) {
                    ROI shapeRight = RoiTools.combineROIs(rightROI, annotation.getROI(), RoiTools.CombineOp.INTERSECT);
                    if (!shapeRight.isEmpty()) {
                        PathObject objectRight = PathObjects.createAnnotationObject(shapeRight, annotation.getPathClass(), duplicateMeasurements(annotation.getMeasurementList()));
                        objectRight.setName(annotation.getName());
                        objectRight.setPathClass(QP.getDerivedPathClass(QP.getPathClass("Right"), annotation.getPathClass().getName()));
                        objectRight.setColor(annotation.getColor());
                        objectRight.setLocked(true);
                        splitObjects.add(objectRight);
                    }
                }

            }
            return splitObjects;
        } else {
            return annotations;
        }
    }

    public static void loadWarpedAtlasAnnotations(AtlasOntology ontology, ImageData<BufferedImage> imageData, String atlasName, boolean splitLeftRight) {
        PathObject object = getWarpedAtlasRegions(ontology, imageData, atlasName, splitLeftRight);
        if (object!=null) {
            imageData.getHierarchy().addObject(object);
            imageData.getHierarchy().fireHierarchyChangedEvent(AtlasTools.class);
        }
    }

    private static MeasurementList duplicateMeasurements(MeasurementList measurements) {
        MeasurementList list = MeasurementListFactory.createMeasurementList(measurements.size(), MeasurementList.MeasurementListType.GENERAL);

        for (String name : measurements.getMeasurementNames()) {
            double value = measurements.get(name);
            list.put(name, value);
        }
        return list;
    }

    public static List<String> getAvailableAtlasRegistration(ImageData<BufferedImage> imageData) {
        Project<BufferedImage> project = qupath.getProject();
        ProjectImageEntry<BufferedImage> entry = project.getEntry(imageData);

        List<String> atlasNames = new ArrayList<>();

        // Now look for files named "ABBA-Roiset-"+atlasName+".zip";
        // regex ( found with https://regex101.com/): (ABBA-RoiSet-)(.+)(.zip)

        Pattern atlasNamePattern = Pattern.compile("(ABBA-RoiSet-)(.+)(.zip)");

        try {
            Files.list(entry.getEntryPath())
                    .forEach(path -> {
                        Matcher matcher = atlasNamePattern.matcher(path.getFileName().toString());
                        if (matcher.matches())
                            atlasNames.add(matcher.group(2)); // Why in hell is this one-based ?
                    });
        } catch (IOException e) {
            e.printStackTrace();
        }

        return atlasNames;
    }

    public static RealTransform getAtlasToPixelTransform(ImageData<BufferedImage> imageData) {
        String atlasName = getAvailableAtlasRegistration(imageData).get(0);
        return getAtlasToPixelTransform(imageData, atlasName); // Needs the inverse transform
    }

    public static RealTransform getAtlasToPixelTransform(ImageData<BufferedImage> imageData, String atlasName) {
        Project<BufferedImage> project = qupath.getProject();
        ProjectImageEntry<BufferedImage> entry = project.getEntry(imageData);
        File fTransform = new File(entry.getEntryPath().toString(),"ABBA-Transform-"+atlasName+".json");
        if (!fTransform.exists()) {
            logger.error("ABBA transformation file not found for entry "+entry);
            return null;
        }
        RealTransform transformWithoutServerTransform = Warpy.getRealTransform(fTransform);

        // Rotation for rotated servers
        ImageServer<?> server = imageData.getServer();

        AffineTransform3D transform = new AffineTransform3D();

        if (server instanceof RotatedImageServer) {
            // The roi will need to be transformed before being imported
            // First : get the rotation
            RotatedImageServer ris = (RotatedImageServer) server;
            switch (ris.getRotation()) {
                case ROTATE_NONE: // No rotation.
                    break;
                case ROTATE_90:
                    // Rotate 90 degrees clockwise.
                    transform.set(new double[]{
                            0.0,-1.0, 0.0, server.getWidth(),
                            1.0, 0.0, 0.0, 0.0,
                            0.0, 0.0, 1.0, 0.0
                    });
                    break;
                case ROTATE_180: // Rotate 180 degrees.
                    transform.set(new double[]{
                           -1.0, 0.0, 0.0, server.getWidth(),
                            0.0,-1.0, 0.0, server.getHeight(),
                            0.0, 0.0, 1.0, 0.0
                    });
                    break;
                case ROTATE_270: // Rotate 270 degrees
                    // Rotate 90 degrees clockwise.
                    transform.set(new double[]{
                            0.0, 1.0, 0.0, 0.0,
                           -1.0, 0.0, 0.0, server.getHeight(),
                            0.0, 0.0, 1.0, 0.0
                    });
                    break;
                default:
                    System.err.println("Unknown rotation for rotated image server: "+ris.getRotation());
            }
        }

        InvertibleRealTransformSequence irts = new InvertibleRealTransformSequence();
        irts.add((InvertibleRealTransform) transformWithoutServerTransform);
        irts.add(transform);

        return irts;

    }

    public static Set<String> getNamingProperties(AtlasOntology ontology) {
        return ontology.getRoot().data().keySet();
    }

    public static void loadWarpedAtlasAnnotations(ImageData<BufferedImage> imageData, String namingProperty, boolean splitLeftRight) {
        List<String> atlasNames = AtlasTools.getAvailableAtlasRegistration(imageData);
        if (atlasNames.size()==0) {
            logger.error("No atlas registration found."); // TODO : show an error message for the user
            return;
        }

        String atlasName = atlasNames.get(0);
        if (atlasNames.size()>1) {
            logger.warn("Several atlases registration have been found. Importing atlas: "+atlasName);
        }

        Path ontologyPath = Paths.get(Projects.getBaseDirectory(qupath.getProject()).getAbsolutePath(), atlasName+"-Ontology.json");
        AtlasOntology ontology = AtlasHelper.openOntologyFromJsonFile(ontologyPath.toString());

        if (ontology == null) {
            logger.warn("Missing ontology for atlas "+atlasName+". No file present in path "+ontologyPath);
            return;
        }

        Set<String> namingProperties = AtlasTools.getNamingProperties(ontology);

        if ( !namingProperties.contains( namingProperty ) ) {
            logger.error("Ontology Name Property {} not found.\nAvailable properties are:  {}", namingProperty, namingProperties.toString());
            return;
        }

        ontology.setNamingProperty(namingProperty);

        // Now we have all we need, the name whether to split left and right
        AtlasTools.loadWarpedAtlasAnnotations(ontology, imageData, atlasName, splitLeftRight);
    }
}
