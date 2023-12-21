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
 * This script is not supposed to work in one block. Just copy paste in another script the parts you need
 */


// See forum post https://forum.image.sc/t/qupath-script-to-restrict-cell-detection-to-several-sub-regions-of-the-brain/71707/3 for images

// 1. To clear all objects (take care, it also clear cells (detection objects)!)
clearAllObjects()

// 2. To clear all annotations objects:
removeObjects(getAnnotationObjects(), false) // last argument = keep child objects ?

// 3. To import the atlas regions (take care to not import it several times: clear the objects before)
// Load atlas and name all regions according with their acronym
// Last argument = split left and right regions
qupath.ext.biop.abba.AtlasTools.loadWarpedAtlasAnnotations(getCurrentImageData(), "acronym", true);

// 4. To collect and select a subregion (here the only with the acronym ‘CTXpl’)
// Gets all annotations (=regions) named CTXpl (left and right)
def myObjects = getAllObjects().findAll{it.getName() == 'CTXpl'} // replace 'CTXpl' by any region acronym existing in the atlas

// Then select them
selectObjects(myObjects)

// 5. same as 4., but restricting to the left part of the brain

// Gets all annotations named CTXpl in the left region:
def myLeftObjects = getAnnotationObjects()
                .findAll{it.getName() == 'CTXpl'} // replace 'CTXpl' by any region acronym existing in the atlas
                .findAll{it.getPathClass().isDerivedFrom(getPathClass('Left'))} // select only the ones in the left regions

// Then select them      
selectObjects(myLeftObjects)

// 6. to collect and select subregions from a list

// Gets all annotations which name is contained within a list:
listOfRegionsToSelect=['MPN', 'CTXsp', 'ACAd']


def myObjectsWithinAList = getAnnotationObjects()
                .findAll{it.getName() in listOfRegionsToSelect}
                //.findAll{it.getPathClass().isDerivedFrom(getPathClass('Left'))} // Uncomment this line to get only the objects in the left region

// Then select them             
selectObjects(myObjectsWithinAList)

// 7. to collect all regions except the ones on a list

def myObjectsWithinAList = getAnnotationObjects()
                .findAll{!(it.getPathClass() == null)} // removes null objects
                .findAll{it.getPathClass().isDerivedFrom(getPathClass('Left'))} 

// Gets all annotations except the ones of a list
def objectsOtherThan = getAnnotationObjects() - myObjectsWithinAList
selectObjects(objectsOtherThan)

// 8. removing objects

// In the snippet below, we first selected the objects of interest, then use that to collect the other objects, that we remove with removeObjects

// Gets all annotations which name is contained within a list:
listOfRegionsToSelect=['MPN', 'CTXsp', 'ACAd']


def myObjectsWithinAList = getAnnotationObjects()
                .findAll{it.getName() in listOfRegionsToSelect}

// Gets all annotations except the ones of a list
def objectsOtherThan = getAnnotationObjects() - myObjectsWithinAList


removeObjects(objectsOtherThan, true)

