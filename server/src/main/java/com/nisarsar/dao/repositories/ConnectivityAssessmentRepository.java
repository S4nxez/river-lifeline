package com.nisarsar.dao.repositories;

import com.nisarsar.dao.model.entities.ConnectivityAssessmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectivityAssessmentRepository
        extends JpaRepository<ConnectivityAssessmentEntity, ConnectivityAssessmentEntity.Key> {}
