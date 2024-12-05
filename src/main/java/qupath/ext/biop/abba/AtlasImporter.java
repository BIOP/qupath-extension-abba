package qupath.ext.biop.abba;

import com.google.gson.Gson;
import ij.gui.Roi;
import net.imglib2.RealPoint;
import net.imglib2.realtransform.InvertibleRealTransform;
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
import qupath.lib.projects.Project;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static qupath.ext.biop.abba.AtlasTools.getLazyNestedBuilders;
import static qupath.ext.biop.abba.AtlasTools.getLazyRotation;

public class AtlasImporter {
    private ImageData<BufferedImage> imageData;
    private String atlasName;
    private File roiFile;
    private String ontologyProperty;
    private Project<BufferedImage> project;
    private boolean splitLeftRight;
    private AtlasOntology ontology;

    public AtlasImporter(ImageData<BufferedImage> imageData) {
        this.project = QP.getProject();
        this.imageData = imageData;
    }

    private enum PreferredNamingProperty {
        ACRONYM("acronym"),
        NAME("name"),
        ID("id");
        private final String value;

        PreferredNamingProperty(String value) { this.value = value; }

        public String getValue() {
            return value;
        }
    }

    final static Logger logger = LoggerFactory.getLogger(AtlasImporter.class);

    public static class AtlasBuilder {
        private ImageData<BufferedImage> imageData;
        private String ontologyProperty;
        private boolean splitLeftRight = true;

        private AtlasBuilder(ImageData<BufferedImage> imageData) {
            this.imageData = imageData;
        }

        AtlasBuilder setOntologyProperty(String ontologyProperty) {
            this.ontologyProperty = ontologyProperty;
            return this;
        }
        AtlasBuilder doNotSplit() {
            this.splitLeftRight = false;
            return this;
        }

        AtlasImporter build() {
            AtlasImporter loader = new AtlasImporter(this.imageData);

            // Load the Atlas
            List<String> atlasNames = loader.getAvailableAtlasNames();
            if (atlasNames.size() == 0) {
                logger.error("No ABBA Atlases found.");
                return null;
            }

            loader.atlasName = atlasNames.get(0);
            logger.info("Atlas Name: {}", loader.atlasName);

            loader.roiFile = loader.getAtlasRoiFile();
            if( !loader.roiFile.exists()) {
                logger.warn("No RoiSet file associated to image {} and Atlas {} found", imageData.getServerMetadata().getName(), loader.atlasName);
            }

            // Load Ontology
            AtlasOntology ontology = loader.getAtlasOntology();
            assert ontology != null;

            loader.ontology = ontology;
            logger.info("Ontology set to {}", ontology.getName());

            //Ontology Naming Property
            if (this.ontologyProperty == null) {
                // Find a default name based on preferences
                Set<String> namingProperties = ontology.getRoot().data().keySet();

                // Default to the first property
                this.ontologyProperty = namingProperties.stream().findFirst().get();

                // Check some common properties in order to use them as names and pathClasses
                for (PreferredNamingProperty p : PreferredNamingProperty.values()) {
                    if (namingProperties.contains(p.getValue())) {
                        this.ontologyProperty = p.getValue();
                        break;
                    }
                }
            }

            loader.ontologyProperty = this.ontologyProperty;
            logger.info("Ontology Naming Property set to '{}'", loader.ontologyProperty);

            loader.splitLeftRight = this.splitLeftRight;
            logger.info("Left/Right splitting set to {}", loader.splitLeftRight);

            return loader;
        }
    }

    public static AtlasBuilder builder(ImageData<BufferedImage> imageData) {
        return new AtlasBuilder(imageData);
    }

    public void loadWarpedAtlasAnnotations() {

        // Now we have all we need, the name whether to split left and right
        imageData.getHierarchy().addObject(getWarpedAtlasRegions());
        imageData.getHierarchy().fireHierarchyChangedEvent(AtlasTools.class);
    }

    PathObject getWarpedAtlasRegions() {

        List<PathObject> annotations = getFlattenedWarpedAtlasRegions();

        if (this.splitLeftRight) {
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
            if ((rootLeft != null) && (rootRight != null)) {
                rootFused = RoiTools.combineROIs(rootLeft.getROI(), rootRight.getROI(), RoiTools.CombineOp.ADD);
            } else if (rootLeft == null) {
                rootFused = RoiTools.combineROIs(ROIs.createEmptyROI(), rootRight.getROI(), RoiTools.CombineOp.ADD);
            } else {
                assert rootRight == null;
                rootFused = RoiTools.combineROIs(rootLeft.getROI(), ROIs.createEmptyROI(), RoiTools.CombineOp.ADD);
            }
            rootObject = PathObjects.createAnnotationObject(rootFused);
            rootObject.setName("Root");
            rootObject.setPathClass(PathClass.fromString("Root"));
            if (rootLeft != null) {
                rootObject.addChildObject(rootLeft);
            }
            if (rootRight != null) {
                rootObject.addChildObject(rootRight);
            }
            return rootObject;
        } else {
            return createAnnotationHierarchy(annotations);
        }

    }

    public List<PathObject> getFlattenedWarpedAtlasRegions() {

        // The ROI Set is the same as the atlas file but ends in zip
        ArrayList<Roi> rois = new ArrayList<>();
        if (this.roiFile.exists()) {
            rois = RoiSetLoader.openRoiSet(this.roiFile);
        }

        // The ontology is what contains all the information about each ROI, their relationships and so on
        logger.info("Loading {} Atlas Regions for {}", rois.size(), project.getEntry(this.imageData).getImageName());

        Roi right = null;
        Roi left = null;

        for (Roi roi : rois) {
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
                    transform = AffineTransform.getRotateInstance(Math.PI / 2.0);
                    transform.translate(0, -metadata.getWidth());
                    break;
                case ROTATE_180: // Rotate 180 degrees.
                    transform = AffineTransform.getRotateInstance(Math.PI);
                    transform.translate(-metadata.getWidth(), -metadata.getHeight());
                    break;
                case ROTATE_270: // Rotate 270 degrees
                    transform = AffineTransform.getRotateInstance(Math.PI * 3.0 / 2.0);
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
            if (finalTransform != null) {
                object = PathObjectTools.transformObject(object, finalTransform, true);
            }

            // Add metadata to object as acquired from the Ontology
            int object_id = Integer.parseInt(roi.getName());
            addOntologyAsMeasurements(object, this.ontology, object_id);

            AtlasNode node = this.ontology.getNodeFromId(object_id);
            String name = node.data().get(this.ontologyProperty);

            object.setPathClass(QP.getPathClass(name));

            object.setName(name);
            object.getMeasurementList().put("ID", node.getId());

            if (node.parent() != null) {
                object.getMeasurementList().put("Parent ID", node.parent().getId());
            }

            // Get some aesthetics right
            object.setLocked(true);
            int[] rgba = node.getColor();
            int color = ColorTools.packRGB(rgba[0], rgba[1], rgba[2]);
            object.setColor(color);
            return object;

        }).collect(Collectors.toList());

        if (this.splitLeftRight) {
            ROI leftROI = null;
            ROI rightROI = null;
            if (left != null) {
                leftROI = IJTools.convertToROI(left, 0, 0, 1, null);
                // Handles rotated image server
                if (finalTransform != null) {
                    PathObject leftObject = PathObjects.createAnnotationObject(leftROI);
                    leftROI = PathObjectTools.transformObject(leftObject, finalTransform, true).getROI();
                }
            }
            if (right != null) {
                rightROI = IJTools.convertToROI(right, 0, 0, 1, null);
                // Handles rotated image server
                if (finalTransform != null) {
                    PathObject rightObject = PathObjects.createAnnotationObject(rightROI);
                    rightROI = PathObjectTools.transformObject(rightObject, finalTransform, true).getROI();
                }
            }
            List<PathObject> splitObjects = new ArrayList<>();
            for (PathObject annotation : annotations) {

                if (leftROI != null) {
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

                if (rightROI != null) {
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

    public void addCCFCoordinates(List<PathObject> objects) {

        RealTransform transform = getAtlasTransform();

        assert transform instanceof InvertibleRealTransform;

        final RealTransform invertedTransform = ((InvertibleRealTransform) transform).inverse();
        objects.forEach(o -> {
            MeasurementList ml = o.getMeasurementList();

            RealPoint coordinates = new RealPoint(o.getROI().getCentroidX(),
                    o.getROI().getCentroidY(),
                    0);
            invertedTransform.apply(coordinates, coordinates);

            // Finally: Add the coordinates as measurements
            ml.put("Allen CCFv3 X mm", coordinates.getDoublePosition(0));
            ml.put("Allen CCFv3 Y mm", coordinates.getDoublePosition(1));
            ml.put("Allen CCFv3 Z mm", coordinates.getDoublePosition(2));
        });
    }

    private List<String> getAvailableAtlasNames() {
        File entryFolder = getEntryFolder(this.imageData);

        List<String> atlasNames = new ArrayList<>();

        // Now look for files named "ABBA-Transform-"+atlasName+".json";
        // regex ( found with https://regex101.com/): (ABBA-Transform-)(.+)(.json)
        Pattern atlasNamePattern = Pattern.compile("(ABBA-Transform-)(.+)(.json)");

        try {
            Files.list(entryFolder.toPath())
                    .forEach(path -> {
                        Matcher matcher = atlasNamePattern.matcher(path.getFileName().toString());
                        if (matcher.matches())
                            atlasNames.add(matcher.group(2)); // Why in hell is this one-based ? the 0 contains the full match
                    });
        } catch (IOException e) {
            e.printStackTrace();
        }
        return atlasNames;
    }

    /**
     * Return the Rois associated to the Atlas Transformation file
     * We need this internally, users should not have to worry about this
     * It should be opened from a ZIP file in the same folder as the Atlas Transform file
     * with the same name.
     *
     * @return a list of (Already transformed) ImageJ Rois
     */
    private File getAtlasRoiFile() {

        File roiSetFile = new File( getEntryFolder(this.imageData), "ABBA-RoiSet-" + this.atlasName + ".zip");

        return roiSetFile;
    }

    public RealTransform getAtlasTransform() {
        File transformFile = new File(getEntryFolder(this.imageData), "ABBA-Transform-" + this.atlasName + ".json");

        if(transformFile.exists()) {
            logger.info("Loading transform from {}", transformFile);
            return Warpy.getRealTransform(transformFile);
        }
        return null;
    }

    public AtlasOntology getAtlasOntology() {

        File ontologyFile = new File(getProjectFolder(this.imageData), this.atlasName+"-Ontology.json");

        if (ontologyFile.exists()) {
            Gson gson = new Gson();
            try {
                FileReader fr = new FileReader(ontologyFile.getAbsoluteFile());
                AtlasHelper.SerializableOntology ontology = gson.fromJson(new FileReader(ontologyFile.getAbsoluteFile()), AtlasHelper.SerializableOntology.class);
                ontology.initialize();
                fr.close();
                return ontology;
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                return null;
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        } else return null;
    }

    private PathObject createAnnotationHierarchy(List<PathObject> annotations) {

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
                System.out.println("No parent, id = " + id);
                // Found the root Path Object
                rootReference.set(annotation);
            }
        });

        // Return just the root annotation from the atlas
        return rootReference.get();
    }

    private static void addOntologyAsMeasurements(PathObject object, AtlasOntology ontology, int object_id) {

        MeasurementList ml = object.getMeasurementList();

        // Get available information from ontology
        // Set<String> namingProperties = ontology.getRoot().data().keySet();
        AtlasNode node = ontology.getNodeFromId(object_id);
        node.data().forEach((key, value) -> {
            try {
                ml.put(key, Double.parseDouble(value));
            } catch (NumberFormatException e) {
                //HAHA so what?
            }
        });
    }

    private MeasurementList duplicateMeasurements(MeasurementList measurements) {
        MeasurementList list = MeasurementListFactory.createMeasurementList(measurements.size(), MeasurementList.MeasurementListType.GENERAL);

        for (String name : measurements.getMeasurementNames()) {
            double value = measurements.get(name);
            list.put(name, value);
        }
        return list;
    }

    private File getEntryFolder( ImageData<BufferedImage> imageData ) {
        return QP.getProject().getEntry(imageData).getEntryPath().toFile();
    }

    private File getProjectFolder( ImageData<BufferedImage> imageData ) {
        return new File( QP.getProject().getPath().toFile().getParent() );
    }



}