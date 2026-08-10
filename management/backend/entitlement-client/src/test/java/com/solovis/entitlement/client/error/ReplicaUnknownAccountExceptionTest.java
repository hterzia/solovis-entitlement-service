package com.solovis.entitlement.client.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solovis.entitlement.core.error.UnknownAccountException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReplicaUnknownAccountExceptionTest {

    @Test
    void isCatchableAsTheCoreUnknownAccountExceptionSoOneHandlerCoversServiceAndSdkCallers() {
        var e = new ReplicaUnknownAccountException("acct_9931", Duration.ofSeconds(3), true);

        assertThat(e).isInstanceOf(UnknownAccountException.class);
        assertThat(e.accountExternalId()).isEqualTo("acct_9931");
    }

    @Test
    void carriesTheEvidenceThatSeparatesAGenuine404FromAnOutagePlusRace() {
        var raced = new ReplicaUnknownAccountException("acct_new", Duration.ofSeconds(120), false);

        assertThat(raced.snapshotAge()).isEqualTo(Duration.ofSeconds(120));
        assertThat(raced.readThroughAttempted()).isFalse();
    }

    @Test
    void snapshotBehindReportsBothVersionsSoACallerCanDecideWhetherToRetryOrProceed() {
        assertThatThrownBy(() -> { throw new SnapshotBehindException(48211L, 48208L); })
            .isInstanceOf(SnapshotBehindException.class)
            .hasMessageContaining("48211")
            .hasMessageContaining("48208");
    }
}
