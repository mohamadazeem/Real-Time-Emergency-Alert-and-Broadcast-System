package edu.ec5205.alert.server;

import edu.ec5205.alert.common.JsonMessageCodec;
import edu.ec5205.alert.common.Messages.AckList;
import edu.ec5205.alert.common.Messages.ErrorMessage;
import edu.ec5205.alert.common.Messages.GetAcks;
import edu.ec5205.alert.common.ProtocolException;
import edu.ec5205.alert.common.ProtocolMessage;
import edu.ec5205.alert.server.storage.AcknowledgementRepository;
import edu.ec5205.alert.server.storage.AlertRepository;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;

/** Read-only recovery of stored acknowledgement status, bounded before enqueueing. */
public final class AcknowledgementQueryService {
    // Every valid row contains at least its 36-byte UUID. This prefix alone cannot fit.
    private static final int MAXIMUM_ROWS = JsonMessageCodec.MAX_FRAME_BYTES / 36 + 1;
    private final AlertRepository alerts;
    private final AcknowledgementRepository acknowledgements;
    private final JsonMessageCodec codec = new JsonMessageCodec();

    public AcknowledgementQueryService(AlertRepository alerts, AcknowledgementRepository acknowledgements) {
        this.alerts = alerts;
        this.acknowledgements = acknowledgements;
    }

    public ProtocolMessage retrieve(GetAcks request) throws SQLException, ProtocolException {
        codec.encode(request);
        if (alerts.findById(request.alertId()).isEmpty()) {
            return new ErrorMessage("UNKNOWN_ALERT", "The referenced alert does not exist.", null);
        }
        var stored = acknowledgements.findByAlertId(request.alertId(), MAXIMUM_ROWS);
        int emptyBytes = encodedSize(new AckList(request.alertId(), List.of()));
        int bytes = emptyBytes;
        for (int index = 0; index < stored.size(); index++) {
            bytes += encodedSize(new AckList(request.alertId(), List.of(stored.get(index)))) - emptyBytes;
            if (index > 0) bytes++;
            if (bytes > JsonMessageCodec.MAX_FRAME_BYTES) {
                return new ErrorMessage("INVALID_MESSAGE",
                        "Acknowledgement list exceeds the 65536-byte frame limit; this protocol has no pagination.", null);
            }
        }
        // If MAXIMUM_ROWS were reached, the UUIDs alone would have triggered the error above.
        // Therefore any successful response contains every row, never just the bounded prefix.
        return new AckList(request.alertId(), stored);
    }

    private int encodedSize(AckList list) throws ProtocolException {
        return codec.encode(list).getBytes(StandardCharsets.UTF_8).length;
    }
}
