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
import qupath.ext.warpy.Warpy;
import qupath.imagej.tools.IJTools;
import qupath.lib.common.ColorTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.RotatedImageServer;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.measurements.MeasurementListFactory;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
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
import java.lang.reflect.Field;
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

    static PathObject getWarpedAtlasRegions(AtlasOntology ontology, ImageData<BufferedImage> imageData, boolean splitLeftRight) {

        List<PathObject> annotations = getFlattenedWarpedAtlasRegions(ontology, imageData, splitLeftRight);

        if (annotations == null) return null;

        PathObject atlasRoot;
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
            ROI rootFused;
            if ((rootLeft!=null)&&(rootRight!=null)) {
                rootFused = RoiTools.combineROIs(rootLeft.getROI(), rootRight.getROI(), RoiTools.CombineOp.ADD);
            } else if (rootLeft==null) {
                rootFused = RoiTools.combineROIs(ROIs.createEmptyROI(), rootRight.getROI(), RoiTools.CombineOp.ADD);
            } else { // rootRight == null
                rootFused = RoiTools.combineROIs(rootLeft.getROI(), ROIs.createEmptyROI(), RoiTools.CombineOp.ADD);
            }
            atlasRoot = PathObjects.createAnnotationObject(rootFused);
            if (rootLeft!=null) {
                atlasRoot.addChildObject(rootLeft);
            }
            if (rootRight!=null) {
                atlasRoot.addChildObject(rootRight);
            }
        } else {
            PathObject root = createAnnotationHierarchy(annotations);
            if (root==null)
                return null;
            atlasRoot = PathObjects.createAnnotationObject(root.getROI());
            atlasRoot.addChildObject(root);
        }
        atlasRoot.setName("Root");
        atlasRoot.setLocked(true);
        return atlasRoot;
    }

    public static List<PathObject> getFlattenedWarpedAtlasRegions(AtlasOntology ontology, ImageData<BufferedImage> imageData, boolean splitLeftRight) {
        Project<BufferedImage> project = QP.getProject();

        // Loop through each ImageEntry
        ProjectImageEntry<BufferedImage> entry = project.getEntry(imageData);

        String atlasName = ontology.getName();
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


        AffineTransform transform = null;
        ImageServerBuilder.ServerBuilder<?> serverBuilder = imageData.getServerBuilder();
        if (serverBuilder.getClass().getSimpleName().startsWith("Rotated")) {
            // The roi will need to be transformed before being imported
            // First : get the rotation
            RotatedImageServer.Rotation rotation = null;
            try {
                Field rotationField = serverBuilder.getClass().getDeclaredField("rotation");
                rotationField.setAccessible(true);
                rotation = (RotatedImageServer.Rotation) rotationField.get(serverBuilder);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException("Unknown rotated ImageServerBuilder: "+serverBuilder.getClass());
            }
            ImageServerMetadata metadata = imageData.getServerMetadata();
                switch (rotation) {
                case ROTATE_NONE: // No rotation.
                    break;
                case ROTATE_90: // Rotate 90 degrees clockwise.
                    transform = AffineTransform.getRotateInstance(Math.PI/2.0);
                    transform.translate(0, -metadata.getWidth());
                    break;
                case ROTATE_180: // Rotate 180 degrees.
                    transform = AffineTransform.getRotateInstance(Math.PI);
                    transform.translate(-metadata.getWidth(), -metadata.getHeight());
                    break;
                case ROTATE_270: // Rotate 270 degrees
                    transform = AffineTransform.getRotateInstance(Math.PI*3.0/2.0);
                    transform.translate(-metadata.getHeight(), 0);
                    break;
                default:
                    System.err.println("Unknown rotation for rotated image server: " + rotation);
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
            if ((name == null) && (ontology.getNamingProperty().equals("ID"))) {
                name = Integer.toString(object_id);
            }
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

    @Deprecated
    public static List<PathObject> getFlattenedWarpedAtlasRegions(AtlasOntology ontology, ImageData<BufferedImage> imageData, String atlasName, boolean splitLeftRight) {
        return getFlattenedWarpedAtlasRegions(ontology, imageData, splitLeftRight);
    }

    public static PathObject loadWarpedAtlasAnnotations(AtlasOntology ontology, ImageData<BufferedImage> imageData, boolean splitLeftRight, boolean overwrite) {
        PathObject atlasRoot = getWarpedAtlasRegions(ontology, imageData, splitLeftRight);
        if (atlasRoot!=null) {
            PathObjectHierarchy hierarchy = imageData.getHierarchy();
            PathClass atlasClass = QP.getPathClass(ontology.getName());
            List<PathObject> previousAtlases = QP.getAnnotationObjects()
                    .stream()
                    .filter(o -> "Root".equals(o.getName()) && o.getPathClass() != null && o.getPathClass().equals(atlasClass))
                    .toList();
            if (overwrite && !previousAtlases.isEmpty())
                hierarchy.removeObjects(previousAtlases, false);
            atlasRoot.setPathClass(atlasClass);
            hierarchy.addObject(atlasRoot);
            hierarchy.fireHierarchyChangedEvent(AtlasTools.class);
        }
        return atlasRoot;
    }

    @Deprecated
    public static void loadWarpedAtlasAnnotations(AtlasOntology ontology, ImageData<BufferedImage> imageData, String atlasName, boolean splitLeftRight) {
        loadWarpedAtlasAnnotations(ontology, imageData, splitLeftRight, false);
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
        Project<BufferedImage> project = QP.getProject();
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
        Project<BufferedImage> project = QP.getProject();
        ProjectImageEntry<BufferedImage> entry = project.getEntry(imageData);
        File fTransform = new File(entry.getEntryPath().toString(),"ABBA-Transform-"+atlasName+".json");
        if (!fTransform.exists()) {
            logger.error("ABBA transformation file not found for entry "+entry);
            return null;
        }
        RealTransform transformWithoutServerTransform = Warpy.getRealTransform(fTransform);

        AffineTransform3D transform = new AffineTransform3D();

        ImageServerBuilder.ServerBuilder<?> serverBuilder = imageData.getServerBuilder();
        if (serverBuilder.getClass().getSimpleName().startsWith("Rotated")) {
            // The roi will need to be transformed before being imported
            // First : get the rotation
            RotatedImageServer.Rotation rotation = null;
            try {
                Field rotationField = serverBuilder.getClass().getDeclaredField("rotation");
                rotationField.setAccessible(true);
                rotation = (RotatedImageServer.Rotation) rotationField.get(serverBuilder);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException("Unknown rotated ImageServerBuilder: "+serverBuilder.getClass());
            }
            ImageServerMetadata metadata = imageData.getServerMetadata();
            switch (rotation) {
                case ROTATE_NONE: // No rotation.
                    break;
                case ROTATE_90:
                    // Rotate 90 degrees clockwise.
                    transform.set(new double[]{
                            0.0,-1.0, 0.0, metadata.getWidth(),
                            1.0, 0.0, 0.0, 0.0,
                            0.0, 0.0, 1.0, 0.0
                    });
                    break;
                case ROTATE_180: // Rotate 180 degrees.
                    transform.set(new double[]{
                           -1.0, 0.0, 0.0, metadata.getWidth(),
                            0.0,-1.0, 0.0, metadata.getHeight(),
                            0.0, 0.0, 1.0, 0.0
                    });
                    break;
                case ROTATE_270: // Rotate 270 degrees
                    // Rotate 90 degrees clockwise.
                    transform.set(new double[]{
                            0.0, 1.0, 0.0, 0.0,
                           -1.0, 0.0, 0.0, metadata.getHeight(),
                            0.0, 0.0, 1.0, 0.0
                    });
                    break;
                default:
                    System.err.println("Unknown rotation for rotated image server: " + rotation);
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

    public static PathObject loadWarpedAtlasAnnotations(ImageData<BufferedImage> imageData, String namingProperty, boolean splitLeftRight, boolean overwrite) {
        List<String> atlasNames = AtlasTools.getAvailableAtlasRegistration(imageData);
        if (atlasNames.isEmpty()) {
            logger.error("No atlas registration found."); // TODO : show an error message for the user
            return null;
        }

        String atlasName = atlasNames.get(0);
        if (atlasNames.size()>1) {
            logger.warn("Several atlases registration have been found. Importing atlas: "+atlasName);
        }

        Path ontologyPath = Paths.get(Projects.getBaseDirectory(QP.getProject()).getAbsolutePath(), atlasName+"-Ontology.json");
        AtlasOntology ontology = AtlasHelper.openOntologyFromJsonFile(ontologyPath.toString());

        if (ontology == null) {
            logger.warn("Missing ontology for atlas "+atlasName+". No file present in path "+ontologyPath);
            return null;
        }

        Set<String> namingProperties = AtlasTools.getNamingProperties(ontology);

        if ( !namingProperties.contains( namingProperty ) ) {
            logger.error("Ontology Name Property {} not found.\nAvailable properties are:  {}", namingProperty, namingProperties.toString());
            return null;
        }

        ontology.setNamingProperty(namingProperty);

        // Now we have all we need, the name whether to split left and right
        return loadWarpedAtlasAnnotations(ontology, imageData, splitLeftRight, overwrite);
    }

    @Deprecated
    public static void loadWarpedAtlasAnnotations(ImageData<BufferedImage> imageData, String namingProperty, boolean splitLeftRight) {
        loadWarpedAtlasAnnotations(imageData, namingProperty, splitLeftRight, false);
    }
}
