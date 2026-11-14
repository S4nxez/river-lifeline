package com.riverlifeline.pipeline.application.service;

import com.riverlifeline.pipeline.application.port.out.PassRepository;
import com.riverlifeline.pipeline.application.port.out.PassRepository.StoredPass;
import com.riverlifeline.pipeline.domain.model.area.Area;
import com.riverlifeline.pipeline.domain.model.pass.PassId;
import java.util.List;
import java.util.Objects;

/**
 * What a pass result calls for: the change since the previous shown pass of the same track (and from it to the next
 * one, which a pass arriving out of order changes), then the whole-record classes of the area.
 */
public final class FollowUps {
    private final PassRepository passes;
    private final JobFactory factory;

    public FollowUps(PassRepository passes, JobFactory factory) {
        this.passes = Objects.requireNonNull(passes, "passes");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    public void afterPass(Area area, PassId passId) {
        passes.find(area.id(), passId).ifPresent(pass -> {
            if (pass.shown() && pass.water() != null) {
                List<StoredPass> track =
                        passes.shownOnTrack(area.id(), pass.info().track());
                int at = -1;
                for (int i = 0; i < track.size(); i++) {
                    if (track.get(i).info().id().equals(passId)) {
                        at = i;
                    }
                }
                if (at > 0) {
                    factory.change(area, track.get(at - 1), pass);
                }
                if (at >= 0 && at < track.size() - 1) {
                    factory.change(area, pass, track.get(at + 1));
                }
            }
        });
        factory.classify(area);
    }
}
