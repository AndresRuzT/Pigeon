package io.github.andres.pigeon.application.port.out;

import java.time.Instant;

public interface ClockPort {
    Instant now();
}
