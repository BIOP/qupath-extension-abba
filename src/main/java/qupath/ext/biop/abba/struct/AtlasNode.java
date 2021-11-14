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

import java.util.List;
import java.util.Map;

public interface AtlasNode {
    Integer getId();

    int[] getColor();

    /** Gets the data associated with the node. */
    Map<String, String> data();

    /** Gets the parent of this node. */
    AtlasNode parent();

    /**
     * Gets the node's children. If this list is mutated, the children will be
     * affected accordingly. It is the responsibility of the caller to ensure
     * continued integrity, particularly of parent linkages.
     */
    List<? extends AtlasNode> children();


}
