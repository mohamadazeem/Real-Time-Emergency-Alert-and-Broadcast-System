package edu.ec5205.alert.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Stateless codec. Callers own the streams; use one reader per connection. */
public final class JsonMessageCodec {
    public static final int MAX_FRAME_BYTES = 65_536;
    private final Gson gson = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    /** Returns null only for clean EOF between frames. Reads through exactly one LF. */
    public ProtocolMessage read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        // Never use readLine(): the byte limit must be checked before building a String.
        byte[] frame = new byte[MAX_FRAME_BYTES];
        int length = 0;
        while (true) {
            int next = input.read();
            if (next == -1) {
                if (length == 0) return null;
                throw new EOFException("Incomplete JSON frame: EOF before LF.");
            }
            if (next == '\n') {
                try {
                    String json = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(frame, 0, length)).toString();
                    return decode(json);
                } catch (CharacterCodingException e) {
                    throw new ProtocolException("Frame contains invalid UTF-8.");
                }
            }
            if (length == MAX_FRAME_BYTES) {
                throw new ProtocolException("Frame exceeds 65536 bytes excluding LF; close the connection.");
            }
            frame[length++] = (byte) next;
        }
    }

    /** Writes compact JSON, exactly one LF, then flushes. Does not close output. */
    public void write(OutputStream output, ProtocolMessage message) throws IOException {
        byte[] frame = utf8(encode(message));
        synchronized (output) {
            output.write(frame);
            output.write('\n');
            output.flush();
        }
    }

    /** Serializes and validates a message, without its LF delimiter. */
    public String encode(ProtocolMessage message) throws ProtocolException {
        Objects.requireNonNull(message, "message");
        MessageType type = message.type();
        if (type == null || message.getClass() != type.modelClass()) {
            throw new ProtocolException("Message must use the documented model for its type.");
        }
        JsonObject envelope = new JsonObject();
        envelope.addProperty("version", message.version());
        envelope.addProperty("type", type.name());
        gson.toJsonTree(message).getAsJsonObject().entrySet()
                .forEach(entry -> envelope.add(entry.getKey(), entry.getValue()));
        MessageValidation.validate(envelope);
        String json = gson.toJson(envelope);
        utf8(json); // Enforce the same byte limit for outgoing frames.
        return json;
    }

    /** Deserializes one JSON object, without a framing delimiter. */
    public ProtocolMessage decode(String json) throws ProtocolException {
        utf8(Objects.requireNonNull(json, "json"));
        JsonElement tree;
        try {
            tree = gson.fromJson(json, JsonElement.class);
        } catch (JsonParseException | IllegalStateException e) {
            // Do not include raw JSON or parser details that could expose credentials.
            throw new ProtocolException("Malformed JSON: expected exactly one strict JSON object.");
        }
        if (tree == null || !tree.isJsonObject()) {
            throw new ProtocolException("Frame must contain one JSON object.");
        }
        MessageType type = MessageValidation.validate(tree.getAsJsonObject());
        try {
            return gson.fromJson(tree, type.modelClass());
        } catch (JsonParseException | IllegalArgumentException e) {
            throw new ProtocolException("JSON fields could not be converted to the documented message model.");
        }
    }

    private static byte[] utf8(String json) throws ProtocolException {
        // A UTF-8 string cannot require fewer bytes than its UTF-16 code units.
        if (json.length() > MAX_FRAME_BYTES) {
            throw new ProtocolException("Frame exceeds 65536 bytes excluding LF.");
        }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(json));
            if (encoded.remaining() > MAX_FRAME_BYTES) {
                throw new ProtocolException("Frame exceeds 65536 bytes excluding LF.");
            }
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new ProtocolException("JSON contains invalid Unicode text.");
        }
    }
}
