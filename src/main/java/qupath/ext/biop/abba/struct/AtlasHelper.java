/*-
 * #%L
 * Repo containing a standard API for Atlases and some example ones
 * %%
 * Copyright (C) 2021 EPFL
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package qupath.ext.biop.abba.struct;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Static atlas ontology helper functions
 */
public class AtlasHelper {

    public static Map<Integer, AtlasNode> buildIdToAtlasNodeMap(AtlasNode root) {
        Map<Integer, AtlasNode> result = new HashMap<>();
        return appendToIdToAtlasNodeMap(result, root);
    }

    private static Map<Integer, AtlasNode> appendToIdToAtlasNodeMap(Map<Integer, AtlasNode> map, AtlasNode node) {
        map.put(node.getId(), node);
        node.children().forEach(child -> {
            appendToIdToAtlasNodeMap(map, child);
        });
        return map;
    }

    public static AtlasOntology openOntologyFromJsonFile(String path) {
        File ontologyFile = new File(path);
        if (ontologyFile.exists()) {
            Gson gson = new Gson();
            try {
                FileReader fr = new FileReader(ontologyFile.getAbsoluteFile());
                SerializableOntology ontology = gson.fromJson(new FileReader(ontologyFile.getAbsoluteFile()), SerializableOntology.class);
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

    public static class SerializableOntology implements AtlasOntology{
        String name;
        String namingProperty;
        SerializableAtlasNode root;
        transient Map<Integer, AtlasNode> idToAtlasNodeMap;

        public SerializableOntology(AtlasOntology ontology) {
            this.name = ontology.getName();
            this.root = new SerializableAtlasNode(ontology.getRoot(), null);
            this.namingProperty = ontology.getNamingProperty();
        }

        static void setAllParents(SerializableAtlasNode node) {
            node.children.forEach(child -> {
                    child.setParent(node);
                    setAllParents(child);
                }
            );
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void initialize() throws Exception {
            setAllParents(root);
            idToAtlasNodeMap = AtlasHelper.buildIdToAtlasNodeMap(root);
        }

        @Override
        public void setDataSource(URL dataSource) {

        }

        @Override
        public URL getDataSource() {
            return null;
        }

        @Override
        public AtlasNode getRoot() {
            return root;
        }

        @Override
        public AtlasNode getNodeFromId(int id) {
            return idToAtlasNodeMap.get(id);
        }

        @Override
        public String getNamingProperty() {
            return namingProperty;
        }

        @Override
        public void setNamingProperty(String namingProperty) {
            this.namingProperty = namingProperty;
        }
    }

    public static class SerializableAtlasNode implements AtlasNode {

        final public int id;
        final public int[] color;
        final public Map<String, String> data;
        final public List<SerializableAtlasNode> children;
        transient public SerializableAtlasNode parent;

        public SerializableAtlasNode(AtlasNode node, SerializableAtlasNode parent) {
            this.id = node.getId();
            this.data = node.data();
            this.parent = parent;
            this.color = node.getColor();
            children = new ArrayList<>();
            node.children().forEach(n -> {
                children.add(new SerializableAtlasNode(n, SerializableAtlasNode.this));
            });
        }

        @Override
        public Integer getId() {
            return id;
        }

        @Override
        public int[] getColor() {
            return color;
        }

        @Override
        public Map<String, String> data() {
            return data;
        }

        @Override
        public AtlasNode parent() {
            return parent;
        }

        public void setParent(SerializableAtlasNode parent) {
            this.parent = parent;
        }

        @Override
        public List<? extends AtlasNode> children() {
            return children;
        }
    }

}
