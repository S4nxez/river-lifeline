package com.riverlifeline.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SharedKernelTest {

    @Test
    void aSchemaVersionIsParsedFromSemver() {
        SchemaVersion version = SchemaVersion.parse("1.2.3");
        assertThat(version.major()).isEqualTo(1);
        assertThat(version.minor()).isEqualTo(2);
        assertThat(version.patch()).isEqualTo(3);
        assertThat(version).hasToString("1.2.3");
    }

    @Test
    void anythingButSemverIsRejected() {
        for (String value : new String[] {"1.0", "v1.0.0", "1.0.0-rc1", "", "01.0.0"}) {
            assertThatThrownBy(() -> SchemaVersion.parse(value)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> SchemaVersion.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPointMustBeOnEarth() {
        assertThatThrownBy(() -> new GeoPoint(-200, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeoPoint(0, 91)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeoPoint(Double.NaN, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new GeoPoint(-64.75, -3.4).lon()).isEqualTo(-64.75);
    }

    @Test
    void aBoundingBoxNeedsItsCornersInOrder() {
        assertThatThrownBy(() -> new BoundingBox(-64, -3, -65, -2)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new BoundingBox(-65, -4, -64, -3).north()).isEqualTo(-3);
    }
}
