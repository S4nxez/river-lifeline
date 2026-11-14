package com.nisarsar.dao.model.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "radar_pass")
public class RadarPassEntity {
    @Id
    @Column(name = "id", length = 80, nullable = false)
    private String id;

    @Column(name = "mission", length = 20, nullable = false)
    private String mission;

    @Column(name = "acquired_on", nullable = false)
    private LocalDate acquiredOn;

    @Column(name = "river_level_cm")
    private Integer riverLevelCm;

    protected RadarPassEntity() {}
}
