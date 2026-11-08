package edu.ec5205.alert.server;

import edu.ec5205.alert.common.JsonMessageCodec;
import edu.ec5205.alert.common.Messages.Alert;
import edu.ec5205.alert.common.Messages.AlertAccepted;
import edu.ec5205.alert.common.Messages.CreateAlert;
import edu.ec5205.alert.common.ProtocolException;
import edu.ec5205.alert.server.storage.AlertRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/** Creates server-owned fields and returns acceptance only after the repository commits. */
public final class AlertCreationService {
    private final AlertRepository repository;
    private final ConnectionRegistry registry;
    private final JsonMessageCodec codec = new JsonMessageCodec();

    public AlertCreationService(AlertRepository repository, ConnectionRegistry registry) {
        this.repository = repository;
        this.registry = registry;
    }

    public AlertAccepted create(CreateAlert request) throws ProtocolException, SQLException {
        // Reuse the shared rules even if a future caller constructs a record directly.
        codec.encode(request);
        Alert alert = new Alert(UUID.randomUUID().toString(), Instant.now().toString(),
                request.title(), request.message(), request.category(), request.priority());
        repository.insert(alert);
        registry.broadcast(alert); // Exact fields committed above; nonblocking queue offers only.
        return new AlertAccepted(request.requestId(), alert.id());
    }
}
