import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.util.HexFormat;

/** Read-only logical fingerprint; use a disposable copy when auditing retained user data. */
class InspectDatabase {
    public static void main(String[] args) throws Exception {
        String uri = Path.of(args[0]).toAbsolutePath().toUri().toASCIIString();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + uri + "?mode=ro")) {
            for (String table : new String[]{"alerts", "acknowledgements", "clients"}) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                int count = 0;
                // Schema is owned by this application; primary keys are the first columns.
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery("SELECT * FROM " + table + " ORDER BY 1, 2")) {
                    while (rows.next()) {
                        count++;
                        for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                            String value = rows.getString(column);
                            byte[] text = (value == null ? "<null>" : value).getBytes(StandardCharsets.UTF_8);
                            digest.update(Integer.toString(text.length).getBytes(StandardCharsets.US_ASCII));
                            digest.update((byte)':'); digest.update(text);
                        }
                    }
                }
                System.out.println(table + " count=" + count + " sha256=" + HexFormat.of().formatHex(digest.digest()));
            }
        }
    }
}
