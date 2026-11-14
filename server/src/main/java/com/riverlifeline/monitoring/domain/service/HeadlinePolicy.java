package com.riverlifeline.monitoring.domain.service;

import com.riverlifeline.monitoring.domain.model.pass.StatusCounts;

/** The deterministic headline rules of CONTRACTS.md 3.3 #6, evaluated in order: the worst news first. */
public final class HeadlinePolicy {
    private HeadlinePolicy() {}

    /**
     * @param town the reference town, e.g. {@code Tefé}
     * @param counts the counts of the selected evaluated pass, or null when there is no evaluated pass
     */
    public static Headline headlineFor(String town, StatusCounts counts) {
        if (counts == null) {
            return new Headline("No evaluated radar pass yet.", HeadlineLevel.INFO);
        }
        int isolated = valueOf(counts.isolated());
        int atRisk = valueOf(counts.atRisk());
        if (isolated == 1) {
            return new Headline("1 community has lost the water route to " + town + ".", HeadlineLevel.CRITICAL);
        }
        if (isolated > 1) {
            return new Headline(
                    isolated + " communities have lost the water route to " + town + ".", HeadlineLevel.CRITICAL);
        }
        if (atRisk == 1) {
            return new Headline(
                    "1 community is at risk of losing the water route to " + town + ".", HeadlineLevel.WARNING);
        }
        if (atRisk > 1) {
            return new Headline(
                    atRisk + " communities are at risk of losing the water route to " + town + ".",
                    HeadlineLevel.WARNING);
        }
        return new Headline(
                "All " + valueOf(counts.monitored()) + " monitored communities can reach " + town + " by water.",
                HeadlineLevel.OK);
    }

    private static int valueOf(Integer count) {
        return count == null ? 0 : count;
    }
}
