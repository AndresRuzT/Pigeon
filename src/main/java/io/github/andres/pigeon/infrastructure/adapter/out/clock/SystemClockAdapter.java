package io.github.andres.pigeon.infrastructure.adapter.out.clock;

import io.github.andres.pigeon.application.port.out.ClockPort;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class SystemClockAdapter implements ClockPort {
    @Override
    public Instant now() {
        return Instant.now();
    }
}
