# ABBA should be used with **QuPath 0.4.4**

> [!WARNING]  
> ABBA should be used with **QuPath 0.4.4** (or above). If you are using QuPath 0.4.0, 0.4.1, 0.4.2 or 0.4.3 you NEED TO UPDATE IT!

# QuPath ABBA extension

This repo adds some support for the [ABBA plugin](https://biop.github.io/ijp-imagetoatlas/) in QuPath.

## Installing

You'll also need to install [`qupath-extension-warpy`](https://github.com/BIOP/qupath-extension-warpy) to access all functionalities of this extension.
Download the latest `qupath-extension-abba-[version].jar` file from [releases](https://github.com/BIOP/qupath-extension-abba/releases) and drag it onto the main QuPath window.

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
