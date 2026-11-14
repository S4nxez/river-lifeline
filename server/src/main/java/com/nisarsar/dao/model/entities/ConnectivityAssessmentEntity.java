package com.nisarsar.dao.model.entities;

import com.nisarsar.dao.model.ConnectivityStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@IdClass(ConnectivityAssessmentEntity.Key.class)
@Table(name = "connectivity_assessment")
public class ConnectivityAssessmentEntity {
    @Id
    @Column(name = "pass_id", length = 80, nullable = false)
    private String passId;

    @Id
    @Column(name = "community_id", length = 50, nullable = false)
    private String communityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ConnectivityStatus status;

    @Column(name = "route_width_m")
    private Integer routeWidthM;

    @Column(name = "sentinel1_scene_on")
    private LocalDate sentinel1SceneOn;

    @Column(name = "sentinel1_agrees")
    private Boolean sentinel1Agrees;

    protected ConnectivityAssessmentEntity() {}

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private String passId;
        private String communityId;
    }
}
