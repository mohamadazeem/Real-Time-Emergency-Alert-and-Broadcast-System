package edu.ec5205.alert.common;

import com.google.gson.JsonParser;
import edu.ec5205.alert.common.Messages.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class JsonMessageCodecTest {
    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String TIME = "2026-10-27T09:30:00Z";
    private static final Alert ALERT = new Alert(ID, TIME, "Fire Emergency", "Evacuate.", Category.FIRE, Priority.CRITICAL);
    private static final String ALERT_JSON = "{\"id\":\"" + ID + "\",\"createdAt\":\"" + TIME
            + "\",\"title\":\"Fire Emergency\",\"message\":\"Evacuate.\",\"category\":\"FIRE\",\"priority\":\"CRITICAL\"}";
    private final JsonMessageCodec codec = new JsonMessageCodec();

    static Stream<Arguments> messages() {
        return Stream.of(
                sample(new RegisterClient(ID, "Library PC"), "\"clientId\":\"" + ID + "\",\"name\":\"Library PC\""),
                sample(new Registered(ID), "\"clientId\":\"" + ID + "\""),
                sample(new Login("admin", "secret"), "\"username\":\"admin\",\"password\":\"secret\""),
                sample(new LoginResult(true), "\"success\":true"),
                sample(new CreateAlert(ID, "Fire Emergency", "Evacuate.", Category.FIRE, Priority.CRITICAL),
                        "\"requestId\":\"" + ID + "\",\"title\":\"Fire Emergency\",\"message\":\"Evacuate.\",\"category\":\"FIRE\",\"priority\":\"CRITICAL\""),
                sample(new AlertMessage(ALERT), "\"alert\":" + ALERT_JSON),
                sample(new AlertAccepted(ID, ID), "\"requestId\":\"" + ID + "\",\"alertId\":\"" + ID + "\""),
                sample(new Ack(ID), "\"alertId\":\"" + ID + "\""),
                sample(new AckResult(ID, true), "\"alertId\":\"" + ID + "\",\"success\":true"),
                sample(new AckStatus(ID, ID, TIME), "\"alertId\":\"" + ID + "\",\"clientId\":\"" + ID + "\",\"acknowledgedAt\":\"" + TIME + "\""),
                sample(new GetClients(), ""),
                sample(new ClientList(List.of(new ClientInfo(ID, "Library PC"))),
                        "\"clients\":[{\"clientId\":\"" + ID + "\",\"name\":\"Library PC\"}]"),
                sample(new GetHistory(100), "\"limit\":100"),
                sample(new History(List.of(ALERT)), "\"alerts\":[" + ALERT_JSON + "]"),
                sample(new GetAcks(ID), "\"alertId\":\"" + ID + "\""),
                sample(new AckList(ID, List.of(new Acknowledgement(ID, TIME))),
                        "\"alertId\":\"" + ID + "\",\"acknowledgements\":[{\"clientId\":\"" + ID + "\",\"acknowledgedAt\":\"" + TIME + "\"}]"),
                sample(new ErrorMessage("INVALID_MESSAGE", "Invalid request", null),
                        "\"code\":\"INVALID_MESSAGE\",\"message\":\"Invalid request\"")
        );
    }

    private static Arguments sample(ProtocolMessage model, String fields) {
        return Arguments.of(model, "{\"version\":1,\"type\":\"" + model.type() + "\""
                + (fields.isEmpty() ? "" : "," + fields) + "}");
    }

    @ParameterizedTest
    @MethodSource("messages")
    void everyDocumentedShapeRoundTrips(ProtocolMessage model, String expectedJson) throws Exception {
        assertEquals(JsonParser.parseString(expectedJson), JsonParser.parseString(codec.encode(model)));
        assertEquals(model, codec.decode(expectedJson));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        codec.write(output, model);
        ByteArrayInputStream input = new ByteArrayInputStream(output.toByteArray());
        assertEquals(model, codec.read(input));
        assertNull(codec.read(input));
    }

    @Test
    void samplesCoverEveryType() {
        assertEquals(MessageType.values().length, messages().count());
    }

    @Test
    void utf8AndEscapedNewlinesRemainOneFrame() throws Exception {
        var message = new CreateAlert(ID, "අනතුරු café 🚨", "First line\nSecond line\r\n\"quoted\" \\ text", Category.GENERAL, Priority.LOW);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        codec.write(output, message);
        byte[] bytes = output.toByteArray();
        int lineFeeds = 0;
        for (byte value : bytes) if (value == '\n') lineFeeds++;
        assertEquals(1, lineFeeds);
        assertEquals('\n', bytes[bytes.length - 1]);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\\n"));
        assertEquals(message, codec.read(new ByteArrayInputStream(bytes)));
    }

    @Test
    void fragmentedUtf8AndConsecutiveFramesAreNotLost() throws Exception {
        var first = new RegisterClient(ID, "සිංහල café 🚨");
        var second = new GetHistory(1);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        codec.write(output, first);
        codec.write(output, second);
        // Deliver one byte per fragment, including splits inside UTF-8 characters.
        List<InputStream> fragments = new ArrayList<>();
        for (byte value : output.toByteArray()) fragments.add(new ByteArrayInputStream(new byte[] {value}));
        try (InputStream input = new SequenceInputStream(Collections.enumeration(fragments))) {
            assertEquals(first, codec.read(input));
            assertEquals(second, codec.read(input));
            assertNull(codec.read(input));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "null", "[]", "{", "{\"version\":1,}",
            "{'version':1,'type':'GET_CLIENTS'}", "{version:1,type:GET_CLIENTS}",
            "{\"version\":1,\"type\":\"GET_CLIENTS\"}{}", "/*comment*/{\"version\":1,\"type\":\"GET_CLIENTS\"}"})
    void rejectsMalformedJsonOrNonObjects(String input) {
        assertThrows(ProtocolException.class, () -> codec.read(frame(input)));
    }

    @Test
    void rejectsInvalidUtf8() {
        assertThrows(ProtocolException.class,
                () -> codec.read(new ByteArrayInputStream(new byte[] {(byte) 0xC3, 0x28, '\n'})));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"type\":\"GET_CLIENTS\"}", "{\"version\":1}",
            "{\"version\":\"1\",\"type\":\"GET_CLIENTS\"}", "{\"version\":true,\"type\":\"GET_CLIENTS\"}",
            "{\"version\":1,\"type\":3}", "{\"version\":1,\"type\":null}",
            "{\"version\":1,\"type\":\"LOGIN_RESULT\"}",
            "{\"version\":1,\"type\":\"LOGIN_RESULT\",\"success\":\"true\"}",
            "{\"version\":1,\"type\":\"LOGIN\",\"username\":false,\"password\":\"secret\"}",
            "{\"version\":1,\"type\":\"ALERT\",\"alert\":{}}",
            "{\"version\":1,\"type\":\"CLIENT_LIST\",\"clients\":{}}",
            "{\"version\":1,\"type\":\"HISTORY\",\"alerts\":[null]}",
            "{\"version\":1,\"type\":\"GET_HISTORY\",\"limit\":1.5}",
            "{\"version\":1,\"type\":\"GET_HISTORY\",\"limit\":2147483648}",
            "{\"version\":1,\"type\":\"GET_HISTORY\",\"limit\":0}",
            "{\"version\":1,\"type\":\"GET_HISTORY\",\"limit\":101}"})
    void rejectsMissingFieldsWrongTypesAndInvalidLimits(String input) {
        assertThrows(ProtocolException.class, () -> codec.decode(input));
    }

    @Test
    void unsupportedVersionAndTypeHaveClearErrors() {
        assertTrue(assertThrows(ProtocolException.class,
                () -> codec.decode("{\"version\":2,\"type\":\"GET_CLIENTS\"}"))
                .getMessage().contains("Unsupported protocol version"));
        assertTrue(assertThrows(ProtocolException.class,
                () -> codec.decode("{\"version\":1,\"type\":\"UNKNOWN\"}"))
                .getMessage().contains("Unknown message type"));
        assertThrows(ProtocolException.class, () -> codec.decode("{\"version\":1,\"type\":\"get_clients\"}"));
    }

    @Test
    void rejectsUnknownEnumsAndInvalidAlertTextOnReadAndWrite() throws Exception {
        String valid = codec.encode(new CreateAlert(ID, "Title", "Body", Category.FIRE, Priority.CRITICAL));
        for (String invalid : List.of(valid.replace("FIRE", "UNKNOWN"), valid.replace("CRITICAL", "HIGH"),
                valid.replace("Title", " "), valid.replace("Title", "x".repeat(121)),
                valid.replace("Body", ""), valid.replace("Body", "x".repeat(4001)))) {
            assertThrows(ProtocolException.class, () -> codec.decode(invalid));
        }
        assertThrows(ProtocolException.class,
                () -> codec.encode(new CreateAlert(ID, " ", "Body", Category.FIRE, Priority.LOW)));
        assertThrows(ProtocolException.class,
                () -> codec.encode(new CreateAlert(ID, "Title", "Body", null, Priority.LOW)));
        var boundary = new CreateAlert(ID, "🚨".repeat(120), "x".repeat(4000), Category.FIRE, Priority.MEDIUM);
        assertEquals(boundary, codec.decode(codec.encode(boundary)));
        for (Category category : Category.values()) {
            for (Priority priority : Priority.values()) {
                var message = new CreateAlert(ID, "Title", "Body", category, priority);
                assertEquals(message, codec.decode(codec.encode(message)));
            }
        }
    }

    @Test
    void validatesUuidAndUtcTimestampFormats() throws Exception {
        assertThrows(ProtocolException.class, () -> codec.encode(new Ack("not-a-uuid")));
        assertThrows(ProtocolException.class, () -> codec.encode(new AckStatus(ID, ID, "yesterday")));
        assertThrows(ProtocolException.class,
                () -> codec.encode(new AckStatus(ID, ID, "2026-10-27T09:30:00+05:30")));
        var valid = new AckStatus(ID, ID, TIME);
        assertEquals(valid, codec.decode(codec.encode(valid)));
    }

    @Test
    void optionalErrorRequestIdAndUnknownFieldsPreserveDocumentedBehavior() throws Exception {
        var error = new ErrorMessage("CUSTOM_CODE", "Problem", ID);
        assertEquals(error, codec.decode(codec.encode(error)));
        assertFalse(codec.encode(new ErrorMessage("CUSTOM_CODE", "Problem", null)).contains("requestId"));
        assertEquals(new Ack(ID), codec.decode("{\"version\":1,\"type\":\"ACK\",\"alertId\":\"" + ID
                + "\",\"clientId\":\"ignored-caller-identity\"}"));
    }

    @Test
    void distinguishesCleanEofFromTruncatedFrame() throws Exception {
        assertNull(codec.read(new ByteArrayInputStream(new byte[0])));
        assertThrows(EOFException.class, () -> codec.read(new ByteArrayInputStream(
                "{\"version\":1,\"type\":\"GET_CLIENTS\"}".getBytes(StandardCharsets.UTF_8))));
        assertThrows(EOFException.class, () -> codec.read(new ByteArrayInputStream(new byte[] {(byte) 0xC3})));
    }

    @Test
    void exactly65536BytesIsAcceptedAndMultibyteOverflowIsRejected() throws Exception {
        String prefix = "{\"version\":1,\"type\":\"GET_CLIENTS\",\"extension\":\"";
        String suffix = "\"}";
        int padding = JsonMessageCodec.MAX_FRAME_BYTES - prefix.length() - suffix.length();
        String exact = prefix + "x".repeat(padding) + suffix;
        assertEquals(65536, exact.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(new GetClients(), codec.read(frame(exact)));
        assertThrows(ProtocolException.class, () -> codec.read(frame(exact + " ")));
        String multibyte = prefix + "é".repeat(padding) + suffix;
        assertTrue(multibyte.length() <= 65536);
        assertThrows(ProtocolException.class, () -> codec.read(frame(multibyte)));
    }

    @Test
    void rejectsEndlessFrameImmediatelyAfterLimitWithoutWaitingForEof() {
        class EndlessInput extends InputStream {
            int count;
            @Override public int read() { count++; return 'x'; }
        }
        var input = new EndlessInput();
        assertThrows(ProtocolException.class, () -> codec.read(input));
        assertEquals(65537, input.count);
    }

    @Test
    void oversizedOutgoingMessageWritesNothing() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThrows(ProtocolException.class, () -> codec.write(output,
                new ErrorMessage("INVALID_MESSAGE", "é".repeat(40000), null)));
        assertEquals(0, output.size());
    }

    @Test
    void writesFlushWithoutClosingCallerStream() throws Exception {
        class ObservedOutput extends ByteArrayOutputStream {
            boolean flushed;
            boolean closed;
            @Override public void flush() { flushed = true; }
            @Override public void close() { closed = true; }
        }
        var output = new ObservedOutput();
        codec.write(output, new GetClients());
        assertTrue(output.flushed);
        assertFalse(output.closed);
        assertEquals("{\"version\":1,\"type\":\"GET_CLIENTS\"}\n", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void malformedCompleteFrameDoesNotConsumeTheNextFrame() throws Exception {
        var input = frame("bad JSON\n{\"version\":1,\"type\":\"GET_CLIENTS\"}");
        assertThrows(ProtocolException.class, () -> codec.read(input));
        assertEquals(new GetClients(), codec.read(input));
    }

    @Test
    void passwordIsNotIncludedInModelToStringOrValidationErrors() {
        assertFalse(new Login("admin", "private-secret").toString().contains("private-secret"));
        var error = assertThrows(ProtocolException.class,
                () -> codec.decode("{\"version\":1,\"type\":\"LOGIN\",\"username\":1,\"password\":\"private-secret\"}"));
        assertFalse(error.getMessage().contains("private-secret"));
    }

    @Test
    void validationErrorRetainsOnlyAValidRecoverableRequestId() {
        String invalid = "{\"version\":2,\"type\":\"UNKNOWN\",\"requestId\":\"" + ID + "\"}";
        assertEquals(ID, assertThrows(ProtocolException.class, () -> codec.decode(invalid)).requestId());
        for (String field : List.of("null", "1", "\"not-a-uuid\"", "\"1-1-1-1-1\"")) {
            String json = "{\"version\":2,\"type\":\"UNKNOWN\",\"requestId\":" + field + "}";
            assertNull(assertThrows(ProtocolException.class, () -> codec.decode(json)).requestId());
        }
        assertNull(assertThrows(ProtocolException.class, () -> codec.decode("not JSON")).requestId());
    }

    private static ByteArrayInputStream frame(String json) {
        return new ByteArrayInputStream((json + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
