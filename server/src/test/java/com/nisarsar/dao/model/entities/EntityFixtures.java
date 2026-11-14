package com.nisarsar.dao.model.entities;

import com.nisarsar.dao.model.CommunityKind;
import com.nisarsar.dao.model.ConnectivityStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Builds JPA entities for tests: their no-arg constructors are protected, so only this package can call them. */
public final class EntityFixtures {
    private EntityFixtures() {}

    public static CommunityEntity community(String id, String name, CommunityKind kind) {
        CommunityEntity community = new CommunityEntity();
        community.setId(id);
        community.setName(name);
        community.setKind(kind);
        community.setLatitude(new BigDecimal("-3.354000"));
        community.setLongitude(new BigDecimal("-64.711000"));
        return community;
    }

    public static RadarPassEntity pass(String id, String acquiredOn, Integer riverLevelCm) {
        RadarPassEntity pass = new RadarPassEntity();
        pass.setId(id);
        pass.setMission("NISAR");
        pass.setAcquiredOn(LocalDate.parse(acquiredOn));
        pass.setRiverLevelCm(riverLevelCm);
        return pass;
    }

    public static ConnectivityAssessmentEntity assessment(
            String passId, String communityId, ConnectivityStatus status, Integer routeWidthM) {
        ConnectivityAssessmentEntity assessment = new ConnectivityAssessmentEntity();
        assessment.setPassId(passId);
        assessment.setCommunityId(communityId);
        assessment.setStatus(status);
        assessment.setRouteWidthM(routeWidthM);
        return assessment;
    }

    public static ConnectivityAssessmentEntity crossChecked(
            ConnectivityAssessmentEntity assessment, String sentinel1SceneOn, boolean agrees) {
        assessment.setSentinel1SceneOn(LocalDate.parse(sentinel1SceneOn));
        assessment.setSentinel1Agrees(agrees);
        return assessment;
    }
}
