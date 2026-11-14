package com.nisarsar.domain.services;

import com.nisarsar.dao.model.CommunityKind;
import com.nisarsar.dao.model.Connectivity.Assessment;
import com.nisarsar.dao.model.Connectivity.CommunityHistory;
import com.nisarsar.dao.model.Connectivity.CommunityStatus;
import com.nisarsar.dao.model.Connectivity.CrossCheck;
import com.nisarsar.dao.model.Connectivity.Overview;
import com.nisarsar.dao.model.Connectivity.PassSummary;
import com.nisarsar.dao.model.Connectivity.Place;
import com.nisarsar.dao.model.Connectivity.StatusCounts;
import com.nisarsar.dao.model.ConnectivityStatus;
import com.nisarsar.dao.model.entities.CommunityEntity;
import com.nisarsar.dao.model.entities.ConnectivityAssessmentEntity;
import com.nisarsar.dao.model.entities.RadarPassEntity;
import com.nisarsar.dao.repositories.CommunityRepository;
import com.nisarsar.dao.repositories.ConnectivityAssessmentRepository;
import com.nisarsar.dao.repositories.RadarPassRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** The monitor's read side: what each radar pass says about the water route of each community to the hub. */
@Service
@RequiredArgsConstructor
public class ConnectivityService {
    private final CommunityRepository communities;
    private final RadarPassRepository passes;
    private final ConnectivityAssessmentRepository assessments;

    public Overview overview() {
        List<CommunityEntity> all = communities.findAll();
        Map<String, List<Assessment>> histories = histories();
        List<PassSummary> summaries = passes();

        Place hub = all.stream()
                .filter(community -> community.getKind() == CommunityKind.HUB)
                .findFirst()
                .map(ConnectivityService::place)
                .orElse(null);
        List<CommunityStatus> statuses = all.stream()
                .filter(community -> community.getKind() != CommunityKind.HUB)
                .sorted(Comparator.comparing(CommunityEntity::getName))
                .map(community -> status(community, histories.getOrDefault(community.getId(), List.of())))
                .toList();

        return new Overview(hub, summaries.isEmpty() ? null : summaries.getLast(), statuses);
    }

    /** Every assessment of a monitored community, oldest first; empty for an unknown id or the hub. */
    public Optional<CommunityHistory> history(String communityId) {
        return communities
                .findById(communityId)
                .filter(community -> community.getKind() != CommunityKind.HUB)
                .map(community -> new CommunityHistory(
                        community.getId(),
                        community.getName(),
                        community.getKind(),
                        community.getLatitude().doubleValue(),
                        community.getLongitude().doubleValue(),
                        histories().getOrDefault(community.getId(), List.of())));
    }

    /** Every pass, oldest first, with how many communities it found in each status. */
    public List<PassSummary> passes() {
        Map<String, List<ConnectivityAssessmentEntity>> byPass =
                assessments.findAll().stream().collect(Collectors.groupingBy(ConnectivityAssessmentEntity::getPassId));
        return passes.findAll().stream()
                .sorted(Comparator.comparing(RadarPassEntity::getAcquiredOn))
                .map(pass -> new PassSummary(
                        pass.getId(),
                        pass.getMission(),
                        pass.getAcquiredOn().toString(),
                        pass.getRiverLevelCm(),
                        counts(byPass.getOrDefault(pass.getId(), List.of()))))
                .toList();
    }

    /**
     * The latest assessment that saw the community: a pass that did not cover it (no data) doesn't hide what the
     * previous ones found. Only when no pass ever saw it is the latest assessment a no data one.
     */
    static Assessment latest(List<Assessment> history) {
        return history.reversed().stream()
                .filter(assessment -> assessment.status() != ConnectivityStatus.NO_DATA)
                .findFirst()
                .orElse(history.isEmpty() ? null : history.getLast());
    }

    private Map<String, List<Assessment>> histories() {
        Map<String, RadarPassEntity> passById =
                passes.findAll().stream().collect(Collectors.toMap(RadarPassEntity::getId, Function.identity()));
        return assessments.findAll().stream()
                .filter(assessment -> passById.containsKey(assessment.getPassId()))
                .sorted(Comparator.comparing(
                        assessment -> passById.get(assessment.getPassId()).getAcquiredOn()))
                .collect(Collectors.groupingBy(
                        ConnectivityAssessmentEntity::getCommunityId,
                        Collectors.mapping(
                                assessment -> assessment(assessment, passById.get(assessment.getPassId())),
                                Collectors.toList())));
    }

    private static Assessment assessment(ConnectivityAssessmentEntity assessment, RadarPassEntity pass) {
        CrossCheck sentinel1 = assessment.getSentinel1SceneOn() == null
                ? null
                : new CrossCheck(
                        assessment.getSentinel1SceneOn().toString(),
                        Boolean.TRUE.equals(assessment.getSentinel1Agrees()));
        return new Assessment(
                pass.getId(),
                pass.getAcquiredOn().toString(),
                assessment.getStatus(),
                assessment.getRouteWidthM(),
                sentinel1);
    }

    private static CommunityStatus status(CommunityEntity community, List<Assessment> history) {
        return new CommunityStatus(
                community.getId(),
                community.getName(),
                community.getKind(),
                community.getLatitude().doubleValue(),
                community.getLongitude().doubleValue(),
                latest(history));
    }

    private static Place place(CommunityEntity community) {
        return new Place(
                community.getId(),
                community.getName(),
                community.getKind(),
                community.getLatitude().doubleValue(),
                community.getLongitude().doubleValue());
    }

    private static StatusCounts counts(List<ConnectivityAssessmentEntity> assessments) {
        Map<ConnectivityStatus, Long> counts = assessments.stream()
                .collect(Collectors.groupingBy(ConnectivityAssessmentEntity::getStatus, Collectors.counting()));
        return new StatusCounts(
                counts.getOrDefault(ConnectivityStatus.CONNECTED, 0L),
                counts.getOrDefault(ConnectivityStatus.AT_RISK, 0L),
                counts.getOrDefault(ConnectivityStatus.ISOLATED, 0L),
                counts.getOrDefault(ConnectivityStatus.NO_DATA, 0L));
    }
}
