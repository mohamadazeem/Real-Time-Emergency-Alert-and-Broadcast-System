package edu.ec5205.alert.server;

import edu.ec5205.alert.common.JsonMessageCodec;
import edu.ec5205.alert.common.Messages.ErrorMessage;
import edu.ec5205.alert.common.Messages.GetHistory;
import edu.ec5205.alert.common.Messages.History;
import edu.ec5205.alert.common.ProtocolException;
import edu.ec5205.alert.common.ProtocolMessage;
import edu.ec5205.alert.server.storage.AlertRepository;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;

/** Loads a bounded snapshot and checks the complete frame before it reaches a writer. */
public final class HistoryService {
    private final AlertRepository alerts;
    private final JsonMessageCodec codec = new JsonMessageCodec();

    public HistoryService(AlertRepository alerts) { this.alerts = alerts; }

    public ProtocolMessage retrieve(GetHistory request) throws SQLException, ProtocolException {
        codec.encode(request); // Also enforce 1–100 for direct application callers.
        History history = new History(alerts.recent(request.limit()));
        int emptyBytes = encodedSize(new History(List.of()));
        int bytes = emptyBytes;
        for (int index = 0; index < history.alerts().size(); index++) {
            // A singleton has the identical envelope/brackets. Add just its alert and a comma.
            // The shared codec accounts for UTF-8, JSON escaping, and model validation.
            bytes += encodedSize(new History(List.of(history.alerts().get(index)))) - emptyBytes;
            if (index > 0) bytes++;
            if (bytes > JsonMessageCodec.MAX_FRAME_BYTES) {
                return new ErrorMessage("INVALID_MESSAGE",
                        "Requested history exceeds the 65536-byte frame limit. Retry with a smaller limit.", null);
            }
        }
        return history;
    }

    private int encodedSize(History history) throws ProtocolException {
        return codec.encode(history).getBytes(StandardCharsets.UTF_8).length;
    }
}
