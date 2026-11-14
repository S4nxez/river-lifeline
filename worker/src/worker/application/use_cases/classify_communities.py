from worker.application import results
from worker.application.use_cases import inputs_of
from worker.domain.connectivity import ClassifyCommunitiesInputs, classify
from worker.domain.jobs import Job, JobResult


class ClassifyCommunities:
    """Whole-record class of every community and the per-pass counts over the monitored ones."""

    def execute(self, job: Job) -> JobResult:
        inputs = inputs_of(job, ClassifyCommunitiesInputs)
        classification = classify(
            inputs.area_id, inputs.access_radius_m, list(inputs.community_ids), list(inputs.passes)
        )
        return JobResult(job.id, results.classification(classification))
