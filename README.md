# QuPath ABBA extension

This repo adds some support for the [ABBA plugin](https://go.epfl.ch/abba) in QuPath.

## Installing

Download the latest zip release of this extension ([releases](https://github.com/BIOP/qupath-extension-abba/releases)), unzip it and drag the jar files onto the main QuPath window.

If you haven't installed any extensions before, you'll be prompted to select a QuPath user directory.
The extension will then be copied to a location inside that directory.

You might then need to restart QuPath (but not your computer).

## Citing

If you use ABBA in your work, please cite the paper below, currently in pre-print:

> [!IMPORTANT]
> Chiaruttini, N., Castoldi, C. et al. **ABBA, a novel tool for whole-brain mapping, reveals brain-wide differences in immediate early genes induction following learning**. _bioRxiv_ (2024).\
> [https://doi.org/10.1101/2024.09.06.611625](https://doi.org/10.1101/2024.09.06.611625)

## Building

You can build the QuPath ABBA extension from source with

```bash
gradlew clean build
```

The output will be under `build/libs`.

* `clean` removes anything old
* `build` builds the QuPath extension as a *.jar* file and adds it to `libs` 
