"""Rasters as the contract moves them: raw little-endian arrays described by a RasterMeta (api/worker-api.md)."""

from dataclasses import dataclass
from enum import IntEnum, StrEnum

from worker.domain.errors import InvalidJob

# The path every raster a job references lives under: the worker never downloads from anywhere else
RASTER_PATH = "/internal/worker/rasters/"


class DType(StrEnum):
    UINT8 = "uint8"
    INT16 = "int16"
    FLOAT32 = "float32"

    @property
    def size(self) -> int:
        return {DType.UINT8: 1, DType.INT16: 2, DType.FLOAT32: 4}[self]


class Unit(StrEnum):
    LINEAR_POWER = "linear_power"
    DB = "db"
    PERCENT = "percent"
    CLASS = "class"


# Class codes, fixed by the contract
class WaterClass(IntEnum):
    DRY = 0
    WIDE = 1
    NARROW = 2
    VEGETATED = 3
    NO_DATA = 255


class Sentinel1Class(IntEnum):
    UNDECIDED = 0
    WATER = 1
    LAND = 2
    NOT_SEEN = 255


class ChangeClass(IntEnum):
    UNCHANGED = 0
    LOST = 1
    GAINED = 2
    NOT_JUDGED = 255


# Occurrence rasters: 0-100 % of the months with water, this value where there is no data
OCCURRENCE_NO_DATA = 255


@dataclass(frozen=True)
class XY:
    x: float
    y: float


@dataclass(frozen=True)
class RasterMeta:
    """Layout of a raw raster body. `origin` is the centre of pixel (0, 0) in `crs`; `step` is signed."""

    width: int
    height: int
    dtype: DType
    # A number, "NaN" (float rasters) or None when every value is data
    nodata: float | str | None
    unit: Unit
    crs: str
    origin: XY
    step: XY

    def __post_init__(self) -> None:
        if self.width < 1 or self.height < 1:
            raise ValueError(f"a raster needs at least one pixel, got {self.width} x {self.height}")
        if isinstance(self.nodata, str) and (self.nodata != "NaN" or self.dtype != DType.FLOAT32):
            raise ValueError(f"nodata {self.nodata!r} is only allowed as 'NaN' on float32 rasters")

    @property
    def byte_length(self) -> int:
        return self.width * self.height * self.dtype.size

    def same_grid(self, other: "RasterMeta") -> bool:
        """Whether both rasters cover the same pixels (size, CRS, origin and step)."""
        return (self.width, self.height, self.crs, self.origin, self.step) == (
            other.width,
            other.height,
            other.crs,
            other.origin,
            other.step,
        )


@dataclass(frozen=True)
class RasterRef:
    """A raster the backend serves at `href` (a backend path, never an absolute URL)."""

    href: str
    meta: RasterMeta

    def __post_init__(self) -> None:
        if not self.href.startswith(RASTER_PATH) or "://" in self.href or ".." in self.href:
            raise InvalidJob(f"raster href {self.href!r} is not a backend raster path")


@dataclass(frozen=True)
class Raster:
    """A raster in memory: its layout and its raw little-endian body."""

    meta: RasterMeta
    data: bytes

    def __post_init__(self) -> None:
        if len(self.data) != self.meta.byte_length:
            raise InvalidJob(
                f"a {self.meta.width} x {self.meta.height} {self.meta.dtype} raster takes {self.meta.byte_length} "
                f"bytes, got {len(self.data)}"
            )
