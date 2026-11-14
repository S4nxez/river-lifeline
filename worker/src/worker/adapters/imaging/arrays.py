"""Rasters of the contract <-> numpy arrays."""

import math

import numpy as np

from worker.domain.rasters import XY, DType, Raster, RasterMeta, Unit, WaterClass

_NUMPY = {DType.UINT8: "<u1", DType.INT16: "<i2", DType.FLOAT32: "<f4"}


def to_array(raster: Raster) -> np.ndarray:
    """The raster as a (height, width) array, read only (it shares the raster's bytes)."""
    meta = raster.meta
    return np.frombuffer(raster.data, dtype=_NUMPY[meta.dtype]).reshape(meta.height, meta.width)


def to_float(raster: Raster) -> np.ndarray:
    """The raster as a writable float32 array with NaN where it has no data."""
    values = to_array(raster).astype(np.float32)
    nodata = raster.meta.nodata
    if isinstance(nodata, float) and not math.isnan(nodata):
        values[values == np.float32(nodata)] = np.nan
    return values


def db_raster(db: np.ndarray, origin: XY, step: XY, crs: str) -> Raster:
    """A float32 dB raster, NaN where there is no data."""
    height, width = db.shape
    meta = RasterMeta(width, height, DType.FLOAT32, "NaN", Unit.DB, crs, origin, step)
    return Raster(meta, np.ascontiguousarray(db, dtype="<f4").tobytes())


def class_raster(codes: np.ndarray, origin: XY, step: XY, crs: str) -> Raster:
    """A uint8 class raster, 255 where there is no data."""
    height, width = codes.shape
    meta = RasterMeta(width, height, DType.UINT8, float(WaterClass.NO_DATA), Unit.CLASS, crs, origin, step)
    return Raster(meta, np.ascontiguousarray(codes, dtype="<u1").tobytes())
