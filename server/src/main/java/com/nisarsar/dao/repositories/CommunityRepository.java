package com.nisarsar.dao.repositories;

import com.nisarsar.dao.model.entities.CommunityEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityRepository extends JpaRepository<CommunityEntity, String> {}
