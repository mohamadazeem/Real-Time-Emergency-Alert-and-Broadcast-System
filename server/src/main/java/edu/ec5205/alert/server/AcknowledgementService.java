package edu.ec5205.alert.server;

import edu.ec5205.alert.common.Messages.Acknowledgement;
import edu.ec5205.alert.common.Messages.AckStatus;
import edu.ec5205.alert.server.storage.AcknowledgementRepository;
import edu.ec5205.alert.server.storage.AlertRepository;
import java.sql.SQLException;
import java.time.Instant;

/** Stores user acknowledgements; queue delivery happens only after the insert commits. */
public final class AcknowledgementService {
    private final AlertRepository alerts;
    private final AcknowledgementRepository acknowledgements;
    private final ConnectionRegistry registry;

    public AcknowledgementService(AlertRepository alerts, AcknowledgementRepository acknowledgements,
                                  ConnectionRegistry registry) {
        this.alerts = alerts;
        this.acknowledgements = acknowledgements;
        this.registry = registry;
    }

    /** False means unknown alert. Both a new insert and an existing pair return true. */
    public boolean acknowledge(String alertId, String registeredClientId) throws SQLException {
        var alert = alerts.findById(alertId);
        if (alert.isEmpty()) return false;
        var acknowledgement = new Acknowledgement(registeredClientId, Instant.now().toString());
        if (acknowledgements.save(alert.get().id(), acknowledgement)) {
            registry.notifyAdmins(new AckStatus(alert.get().id(), registeredClientId, acknowledgement.acknowledgedAt()));
        }
        return true;
    }
}
