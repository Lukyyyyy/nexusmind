package com.luky.nexusmind;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class NexusMindApplicationTests {

    @Test
    void applicationEntryPointIsAvailable() throws NoSuchMethodException {
        assertNotNull(NexusMindApplication.class.getMethod("main", String[].class));
    }

}
