package edu.ec5205.alert.admin;

import edu.ec5205.alert.admin.network.AdminConnection;
import edu.ec5205.alert.admin.service.AdminAuthService;
import edu.ec5205.alert.admin.service.AlertService;
import edu.ec5205.alert.admin.ui.CreateAlertPanel;
import edu.ec5205.alert.admin.ui.DashboardFrame;
import edu.ec5205.alert.admin.ui.LoginFrame;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class AdminUITest {

    @BeforeAll
    static void checkGraphicalEnvironment() {
        // Only run Swing frame creation if the environment is not headless
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Skipping UI rendering tests in headless environment");
    }

    @Test
    @DisplayName("LoginFrame initializes with correct defaults")
    void loginFrameDefaults() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LoginFrame frame = new LoginFrame();
            assertNotNull(frame.getTitle());
            assertTrue(frame.getTitle().contains("Administrator Login"));
            frame.dispose();
        });
    }

    @Test
    @DisplayName("CreateAlertPanel initializes with correct components")
    void createAlertPanelComponents() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try (AdminConnection conn = new AdminConnection("localhost", 5050)) {
                AdminAuthService authService = new AdminAuthService(conn);
                AlertService alertService = new AlertService(conn, authService);
                CreateAlertPanel panel = new CreateAlertPanel(alertService);
                assertNotNull(panel);
            }
        });
    }

    @Test
    @DisplayName("DashboardFrame initializes with all 4 navigation tabs")
    void dashboardFrameTabs() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try (AdminConnection conn = new AdminConnection("localhost", 5050)) {
                AdminAuthService authService = new AdminAuthService(conn);
                AlertService alertService = new AlertService(conn, authService);
                DashboardFrame dashboard = new DashboardFrame(conn, authService, alertService);
                assertNotNull(dashboard);
                assertTrue(dashboard.getTitle().contains("Admin Dashboard"));

                // Verify JTabbedPane exists with 4 tabs
                JTabbedPane tabbedPane = findTabbedPane(dashboard);
                assertNotNull(tabbedPane);
                assertEquals(4, tabbedPane.getTabCount());
                assertEquals("Create Alert", tabbedPane.getTitleAt(0));
                assertEquals("Connected Clients", tabbedPane.getTitleAt(1));
                assertEquals("Alert Monitoring", tabbedPane.getTitleAt(2));
                assertEquals("Alert History", tabbedPane.getTitleAt(3));

                dashboard.dispose();
            }
        });
    }

    private static JTabbedPane findTabbedPane(Container container) {
        for (Component c : container.getComponents()) {
            if (c instanceof JTabbedPane tp) {
                return tp;
            }
            if (c instanceof Container child) {
                JTabbedPane found = findTabbedPane(child);
                if (found != null) return found;
            }
        }
        return null;
    }
}

