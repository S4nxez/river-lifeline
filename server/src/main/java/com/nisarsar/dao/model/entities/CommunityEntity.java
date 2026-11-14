package com.nisarsar.dao.model.entities;

import com.nisarsar.dao.model.CommunityKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "community")
public class CommunityEntity {
    @Id
    @Column(name = "id", length = 50, nullable = false)
    private String id;

    @Column(name = "name", length = 150, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 20, nullable = false)
    private CommunityKind kind;

    @Column(name = "latitude", precision = 9, scale = 6, nullable = false)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6, nullable = false)
    private BigDecimal longitude;

    protected CommunityEntity() {}
}
