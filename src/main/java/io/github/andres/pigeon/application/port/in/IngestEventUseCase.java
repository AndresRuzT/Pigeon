package io.github.andres.pigeon.application.port.in;

public interface IngestEventUseCase {
    IngestEventCommand.IngestResult ingest(IngestEventCommand command);
}
