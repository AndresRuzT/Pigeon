package io.github.andres.pigeon.application.port.in;

import java.util.UUID;

public interface ProcessNotificationUseCase {
    void process(UUID notificationId);
}
