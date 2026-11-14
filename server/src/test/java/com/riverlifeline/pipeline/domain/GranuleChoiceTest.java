package com.riverlifeline.pipeline.domain;

import static com.riverlifeline.pipeline.PipelineTestData.granule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riverlifeline.pipeline.domain.model.pass.Direction;
import com.riverlifeline.pipeline.domain.model.pass.GranuleRef;
import com.riverlifeline.pipeline.domain.model.pass.NisarPass;
import com.riverlifeline.pipeline.domain.service.GranuleChoice;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class GranuleChoiceTest {
    @Test
    void aFileNameDescribesTheGranule() {
        GranuleRef g = GranuleRef.parse(
                "NISAR_L2_PR_GCOV_033_161_A_176_2005_DHDH_A_20261023T100258_20261023T100332_P05036_N_F_J_001.h5",
                "https://x/y.h5",
                "NISAR_L2_GCOV_V1");

        assertThat(g.cycle()).isEqualTo(33);
        assertThat(g.track()).isEqualTo(161);
        assertThat(g.direction()).isEqualTo(Direction.ASCENDING);
        assertThat(g.frame()).isEqualTo(176);
        assertThat(g.mode()).isEqualTo("2005");
        assertThat(g.polarization()).isEqualTo("DHDH");
        assertThat(g.start()).isEqualTo(LocalDateTime.of(2026, 10, 23, 10, 2, 58));
        assertThatThrownBy(() -> GranuleRef.parse("NISAR_L2_PR_GSLC_1.h5", "u", "c"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFinalProductIsPreferredToTheProvisionalOneAndThatToTheBeta() {
        GranuleRef beta = granule("2026-10-23", 161, 176, "NISAR_L2_GCOV_BETA_V1");
        GranuleRef provisional = granule("2026-10-23", 161, 176, "NISAR_L2_GCOV_PROVISIONAL_V1");
        GranuleRef finalProduct = granule("2026-10-23", 161, 176, "NISAR_L2_GCOV_V1");

        List<NisarPass> passes = GranuleChoice.passes(List.of(beta, provisional, finalProduct));

        assertThat(passes).hasSize(1);
        assertThat(passes.get(0).granules()).containsExactly(finalProduct);
        assertThat(GranuleChoice.passes(List.of(beta, provisional)).get(0).granules())
                .containsExactly(provisional);
    }

    @Test
    void twentyMegahertzAndHhComeFirstAndTheCoarseModeIsLeftOut() {
        GranuleRef twenty = granule("2026-10-23", 161, 176, "NISAR_L2_GCOV_V1");
        GranuleRef forty = GranuleRef.parse(twenty.name().replace("_2005_", "_4005_"), "u", "NISAR_L2_GCOV_V1");
        GranuleRef vv = GranuleRef.parse(twenty.name().replace("_DHDH_", "_DVDV_"), "u", "NISAR_L2_GCOV_V1");
        GranuleRef coarse = GranuleRef.parse(twenty.name().replace("_2005_", "_0005_"), "u", "NISAR_L2_GCOV_V1");

        assertThat(GranuleChoice.passes(List.of(forty, vv, twenty)).get(0).granules())
                .containsExactly(twenty);
        // Bandwidth decides before polarisation
        assertThat(GranuleChoice.passes(List.of(vv, forty)).get(0).granules()).containsExactly(vv);
        GranuleRef fortyVv = GranuleRef.parse(forty.name().replace("_DHDH_", "_DVDV_"), "u", "NISAR_L2_GCOV_V1");
        assertThat(GranuleChoice.passes(List.of(fortyVv, forty)).get(0).granules())
                .containsExactly(forty);
        assertThat(GranuleChoice.rank(coarse)).isEmpty();
        assertThat(GranuleChoice.passes(List.of(coarse))).isEmpty();
    }

    @Test
    void theFramesOfOneTrackOnOneDayMakeOnePass() {
        GranuleRef f177 = granule("2026-10-23", 161, 177, "NISAR_L2_GCOV_V1");
        GranuleRef f176 = granule("2026-10-23", 161, 176, "NISAR_L2_GCOV_V1");
        GranuleRef other = granule("2026-10-11", 161, 176, "NISAR_L2_GCOV_V1");

        List<NisarPass> passes = GranuleChoice.passes(List.of(f177, other, f176));

        assertThat(passes)
                .extracting(p -> p.info().id().value())
                .containsExactly("2026-10-11_t161A", "2026-10-23_t161A");
        assertThat(passes.get(1).granules()).containsExactly(f176, f177);
        assertThat(passes.get(1).info().timeUtc()).isEqualTo("10:02");
        assertThat(passes.get(1).info().direction()).isEqualTo(Direction.ASCENDING);
    }
}
