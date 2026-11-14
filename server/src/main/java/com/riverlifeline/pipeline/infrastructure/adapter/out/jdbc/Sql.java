package com.riverlifeline.pipeline.infrastructure.adapter.out.jdbc;

import com.riverlifeline.pipeline.domain.model.raster.DataType;
import com.riverlifeline.pipeline.domain.model.raster.NoData;
import com.riverlifeline.pipeline.domain.model.raster.RasterMeta;
import com.riverlifeline.pipeline.domain.model.raster.RasterUnit;
import com.riverlifeline.pipeline.domain.model.raster.XY;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

/** Column conversions shared by the repositories. */
final class Sql {
    private Sql() {}

    static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    static LocalDate date(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    static Double dbl(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static Boolean bool(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    /** The layout columns of the raster table, read with a column prefix; null when they are empty. */
    static RasterMeta meta(ResultSet rs, String prefix) throws SQLException {
        Integer width = integer(rs, prefix + "width");
        if (width == null) {
            return null;
        }
        return new RasterMeta(
                width,
                rs.getInt(prefix + "height"),
                DataType.fromWire(rs.getString(prefix + "dtype")),
                NoData.parse(rs.getString(prefix + "nodata")),
                RasterUnit.fromWire(rs.getString(prefix + "unit")),
                rs.getString(prefix + "crs"),
                new XY(rs.getDouble(prefix + "origin_x"), rs.getDouble(prefix + "origin_y")),
                new XY(rs.getDouble(prefix + "step_x"), rs.getDouble(prefix + "step_y")));
    }

    /** The layout columns of a raster joined under an alias, for SELECT lists. */
    static String metaColumns(String alias, String prefix) {
        StringBuilder sql = new StringBuilder();
        for (String column : new String[] {
            "id", "width", "height", "dtype", "nodata", "unit", "crs", "origin_x", "origin_y", "step_x", "step_y"
        }) {
            sql.append(", ")
                    .append(alias)
                    .append('.')
                    .append(column)
                    .append(" AS ")
                    .append(prefix)
                    .append(column);
        }
        return sql.toString();
    }
}
