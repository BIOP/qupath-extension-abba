# QuPath ABBA extension

This repo adds some support for the [ABBA plugin](https://go.epfl.ch/abba) in QuPath.

## Installing

In QuPath 0.6, this extension should be installed via the [QuPath BIOP catalog](https://github.com/BIOP/qupath-biop-catalog).

Don't forget to restart QuPath (but not your computer).

## Citing

If you use ABBA in your work, please cite the paper below, currently in pre-print:

> [!IMPORTANT]
> Chiaruttini, N.; Castoldi, C.; Requie, L. et al. **ABBA+BraiAn, an integrated suite for whole-brain mapping, reveals brain-wide differences in immediate-early genes induction upon learning**. _Cell Reports_ (2025).\
> [https://doi.org/10.1016/j.celrep.2025.115876](https://doi.org/10.1016/j.celrep.2025.115876)

## Building

You can build the QuPath ABBA extension from source with

```bash
gradlew clean build
```

The output will be under `build/libs`.

* `clean` removes anything old
* `build` builds the QuPath extension as a *.jar* file and adds it to `libs` 
