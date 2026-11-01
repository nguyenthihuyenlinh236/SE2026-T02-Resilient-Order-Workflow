package com.example.orderworkflow.order;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.fail;

class CiFailureProbeTest {

    @Test
    void intentionallyFailsToVerifyCi() {
        fail("Intentional failure to verify CI");
    }
}
