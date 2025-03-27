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
import qupath.lib.images.ImageData;
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
import java.util.Optional;
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

    static PathObject getWarpedAtlasRegions(AtlasOntology ontology, ImageData<BufferedImage> imageData, String roisetName, boolean splitLeftRight) {

        List<PathObject> annotations = getFlattenedWarpedAtlasRegions(ontology, imageData, roisetName, splitLeftRight);

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

    static Optional<RotatedImageServer.Rotation> getLazyRotation(ImageServerBuilder.ServerBuilder<?> serverBuilder) {
        try {
            Field rotationField = serverBuilder.getClass().getDeclaredField("rotation");
            rotationField.setAccessible(true);
            return Optional.of((RotatedImageServer.Rotation) rotationField.get(serverBuilder));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return Optional.empty();
        }
    }

    static Optional<ImageServerBuilder.ServerBuilder<?>> getLazyWrappedBuilder(ImageServerBuilder.ServerBuilder<?> serverBuilder) {
        try {
            Field builderField = serverBuilder.getClass().getDeclaredField("builder");
            builderField.setAccessible(true);
            return Optional.of((ImageServerBuilder.ServerBuilder<?>) builderField.get(serverBuilder));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return Optional.empty();
        }
    }

    static List<ImageServerBuilder.ServerBuilder<?>> getLazyNestedBuilders(ImageData<?> imageData) {
        Optional<? extends ImageServerBuilder.ServerBuilder<?>> serverBuilder = Optional.of(imageData.getServerBuilder());
        List<ImageServerBuilder.ServerBuilder<?>> builders = new ArrayList<>();
        do {
            serverBuilder.ifPresent(builders::add);
        } while ((serverBuilder = getLazyWrappedBuilder(serverBuilder.get())).isPresent());
        return builders.reversed(); // the order is from the innermost server to the outermost
    }

    private static MeasurementList duplicateMeasurements(MeasurementList measurements) {
        MeasurementList list = MeasurementListFactory.createMeasurementList(measurements.size(), MeasurementList.MeasurementListType.GENERAL);

        for (String name : measurements.getMeasurementNames()) {
            double value = measurements.get(name);
            list.put(name, value);
        }
        return list;
    }

    public static List<String> getAvailableAtlasOntologyFiles() {
        String suffix = "-Ontology.json";
        try {
            return Files.list(QP.getProject().getPath().getParent())
                    .filter(path -> path.toString().endsWith(suffix))
                    .map(path -> path.getFileName().toString())
                    // .map(file -> file.substring(0, file.length() - suffix.length()))
                    .toList();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Returns the name of the available atlas registrations for a given image.<br>
     * NOTE: the returned names are not necessarily the codename of an atlas ontology.
     * @param imageData the data of the image from which to check the available registrations.
     * @return a list of string identifiers.
     */
    public static List<String> getAvailableAtlasRegistration(ImageData<BufferedImage> imageData) {
        Project<BufferedImage> project = QP.getProject();
        ProjectImageEntry<BufferedImage> entry = project.getEntry(imageData);

        List<String> atlasNames = new ArrayList<>();

        // Now look for files named "ABBA-Roiset-"+atlasName+".zip";
        // regex ( found with https://regex101.com/): (ABBA-RoiSet-)(.+)(.zip)

        Pattern roisetPattern = Pattern.compile("(ABBA-RoiSet-)(.+)(.zip)");

        try {
            Files.list(entry.getEntryPath())
                    .forEach(path -> {
                        Matcher matcher = roisetPattern.matcher(path.getFileName().toString());
                        if (matcher.matches())
                            atlasNames.add(matcher.group(2)); // Why in hell is this one-based ?
                    });
        } catch (IOException e) {
            e.printStackTrace();
        }

        return atlasNames;
    }

    public static RealTransform getAtlasToPixelTransform(ImageData<BufferedImage> imageData) {
        String registrationName = getAvailableAtlasRegistration(imageData).get(0);
        return getAtlasToPixelTransform(imageData, registrationName); // Needs the inverse transform
    }

    public static RealTransform getAtlasToPixelTransform(ImageData<BufferedImage> imageData, String transformName) {
        Project<BufferedImage> project = QP.getProject();
        ProjectImageEntry<BufferedImage> entry = project.getEntry(imageData);
        File fTransform = new File(entry.getEntryPath().toString(),"ABBA-Transform-"+transformName+".json");
        if (!fTransform.exists()) {
            logger.error("ABBA transformation file not found for entry "+entry);
            return null;
        }
        RealTransform transformWithoutServerTransform = Warpy.getRealTransform(fTransform);

        AffineTransform3D transform = new AffineTransform3D();
        for (ImageServerBuilder.ServerBuilder<?> serverBuilder: getLazyNestedBuilders(imageData)) {
            // The roi will need to be transformed before being imported
            Optional<RotatedImageServer.Rotation> rotation;
            if ((rotation = getLazyRotation(serverBuilder)).isEmpty())
                // the server is not rotated
                continue;
            ImageServerMetadata metadata = imageData.getServerMetadata();
            switch (rotation.get()) {
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
                    System.err.println("Unknown rotation for rotated image server: " + rotation.get());
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

    public static PathObject loadWarpedAtlasAnnotations(ImageData<BufferedImage> imageData, String ontologyName, String namingProperty, boolean splitLeftRight, boolean overwrite) {
        Path ontologyPath = Paths.get(QP.buildPathInProject(ontologyName+"-Ontology.json")).toAbsolutePath();
        AtlasOntology ontology = AtlasHelper.openOntologyFromJsonFile(ontologyPath.toString());
        if (ontology == null)
            return null;

        Set<String> namingProperties = AtlasTools.getNamingProperties(ontology);

        if ( !namingProperties.contains( namingProperty ) ) {
            logger.error("Ontology Name Property {} not found.\nAvailable properties are:  {}", namingProperty, namingProperties);
            return null;
        }

        ontology.setNamingProperty(namingProperty);

        // Now we have all we need, the name whether to split left and right
        return loadWarpedAtlasAnnotations(ontology, imageData, ontologyName, splitLeftRight, overwrite);
    }

    public static PathObject loadWarpedAtlasAnnotations(AtlasOntology ontology, ImageData<BufferedImage> imageData, String roisetName, boolean splitLeftRight, boolean overwrite) {
        PathObject atlasRoot = getWarpedAtlasRegions(ontology, imageData, roisetName, splitLeftRight);
        if (atlasRoot == null) return null;
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
        return atlasRoot;
    }

    public static PathObject loadWarpedAtlasAnnotations(AtlasOntology ontology, ImageData<BufferedImage> imageData, boolean splitLeftRight, boolean overwrite) {
        PathObject rootAnnotation = loadWarpedAtlasAnnotations(ontology, imageData, ontology.getName(), splitLeftRight, overwrite);
        if (rootAnnotation == null) {
            logger.error("Can't find the registration corresponding to the atlas ontology of the project: {}", ontology.getName()); // TODO : show an error message for the user
            return null;
        }
        return rootAnnotation;
    }

    @Deprecated
    public static PathObject loadWarpedAtlasAnnotations(ImageData<BufferedImage> imageData, String namingProperty, boolean splitLeftRight, boolean overwrite) {
        List<String> ontologyFiles = AtlasTools.getAvailableAtlasOntologyFiles();
        if (ontologyFiles == null || ontologyFiles.isEmpty()) {
            logger.error("No atlas ontology found."); // TODO : show an error message for the user
            return null;
        }

        String ontologyFile = ontologyFiles.get(0);
        if (ontologyFiles.size()>1)
            logger.warn("Several atlas ontologies have been found. Importing ontology: "+ontologyFile);
        return loadWarpedAtlasAnnotations(imageData, ontologyFile.substring(0, ontologyFile.length()-"-Ontology.json".length()), namingProperty, splitLeftRight, overwrite);
    }

    @Deprecated
    public static void loadWarpedAtlasAnnotations(ImageData<BufferedImage> imageData, String namingProperty, boolean splitLeftRight) {
        loadWarpedAtlasAnnotations(imageData, namingProperty, splitLeftRight, false);
    }

    public static List<PathObject> getFlattenedWarpedAtlasRegions(AtlasOntology ontology, ImageData<BufferedImage> imageData, String roisetName, boolean splitLeftRight) {
        Project<BufferedImage> project = QP.getProject();

        // Loop through each ImageEntry
        ProjectImageEntry<BufferedImage> entry = project.getEntry(imageData);

        Path roisetPath = Paths.get(entry.getEntryPath().toString(), "ABBA-RoiSet-"+roisetName+".zip");
        if (!Files.exists(roisetPath)) {
            logger.info("No RoiSets found: {}", roisetPath);
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
        for (ImageServerBuilder.ServerBuilder<?> serverBuilder: getLazyNestedBuilders(imageData)) {
            // The roi will need to be transformed before being imported
            Optional<RotatedImageServer.Rotation> rotation;
            if ((rotation = getLazyRotation(serverBuilder)).isEmpty())
                // the server is not rotated
                continue;
            ImageServerMetadata metadata = imageData.getServerMetadata();
            switch (rotation.get()) {
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
                    System.err.println("Unknown rotation for rotated image server: " + rotation.get());
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

    /**
     * Same as {@link #getFlattenedWarpedAtlasRegions(AtlasOntology, ImageData, String, boolean)}
     * but assuming that the roiset names are the same as the ontology name.
     * @param ontology
     * @param imageData
     * @param splitLeftRight
     * @return
     */
    public static List<PathObject> getFlattenedWarpedAtlasRegions(AtlasOntology ontology, ImageData<BufferedImage> imageData, boolean splitLeftRight) {
        String ontologyName = ontology.getName();
        List<PathObject> warpedRegions = getFlattenedWarpedAtlasRegions(ontology, imageData, ontologyName, splitLeftRight);
        if (warpedRegions == null && "waxholm_sprague_dawley_rat_v4".equals(ontologyName)) {
            logger.info("For the rat ontologies you always need to be explicitly specify the RoiSet name. Usually, 'whs_sd_rat_39um_java' or 'Rat - Waxholm Sprague Dawley V4p2'");
            return null;
        }
        return warpedRegions;
    }
}
