# QuPath ABBA extension

This repo adds some support for the [ABBA plugin](https://abba-documentation.readthedocs.io/en/latest/) in QuPath.

## Installing

Download the latest zip release of this extension ([releases](https://github.com/BIOP/qupath-extension-abba/releases)), unzip it and drag the jar files onto the main QuPath window.

If you haven't installed any extensions before, you'll be prompted to select a QuPath user directory.
The extension will then be copied to a location inside that directory.

You might then need to restart QuPath (but not your computer).

## Citing

TODO

## Building

You can build the QuPath ABBA extension from source with

```bash
gradlew clean build
```

The output will be under `build/libs`.

* `clean` removes anything old
* `build` builds the QuPath extension as a *.jar* file and adds it to `libs` 
