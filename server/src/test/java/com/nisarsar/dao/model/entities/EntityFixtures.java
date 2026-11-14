package com.nisarsar.dao.model.entities;

import java.math.BigDecimal;

/** Builds JPA entities for tests: their no-arg constructors are protected, so only this package can call them. */
public final class EntityFixtures {
    private EntityFixtures() {}

    public static SarSiteEntity site(String id, String changeLevel) {
        SarSiteEntity site = new SarSiteEntity();
        site.setId(id);
        site.setName("Site " + id);
        site.setCountry("Spain");
        site.setLatitude(new BigDecimal("39.304000"));
        site.setLongitude(new BigDecimal("-0.355000"));
        site.setChangeLevel(changeLevel);
        site.setChangeValue("INCREASE");
        site.setCategory("FLOOD");
        site.setDescription("Description of " + id);
        site.setConfidence(new BigDecimal("0.965"));
        return site;
    }

    public static SiteObservationEntity observation(String siteId, String date, String vv, String vh) {
        SiteObservationEntity observation = new SiteObservationEntity();
        observation.setSiteId(siteId);
        observation.setObservationDate(date);
        observation.setVv(new BigDecimal(vv));
        observation.setVh(new BigDecimal(vh));
        return observation;
    }
}
