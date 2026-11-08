package edu.ec5205.alert.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

/** Checks JSON types before Gson can coerce values or default absent fields. */
final class MessageValidation {
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private MessageValidation() {}

    static MessageType validate(JsonObject object) throws ProtocolException {
        int version = integer(required(object, "version"), "version");
        if (version != 1) throw new ProtocolException("Unsupported protocol version; expected 1.");
        String typeName = string(required(object, "type"), "type");
        MessageType type;
        try {
            type = MessageType.valueOf(typeName);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("Unknown message type; expected a documented MessageType name.");
        }
        validateRecord(object, type.modelClass(), type.name());
        return type;
    }

    private static void validateRecord(JsonObject object, Class<?> model, String path) throws ProtocolException {
        for (RecordComponent field : model.getRecordComponents()) {
            String name = field.getName();
            if (model == Messages.ErrorMessage.class && name.equals("requestId") && !object.has(name)) continue;
            JsonElement value = required(object, name);
            String fieldPath = path + "." + name;
            validateValue(value, field.getGenericType(), fieldPath);
            if (field.getType() == String.class) {
                validateText(value.getAsString(), name, model, fieldPath);
            }
        }
        if (model == Messages.GetHistory.class) {
            int limit = integer(object.get("limit"), path + ".limit");
            if (limit < 1 || limit > 100) throw invalid(path + ".limit", "an integer from 1 to 100");
        }
    }

    private static void validateValue(JsonElement value, Type expected, String path) throws ProtocolException {
        if (value.isJsonNull()) throw invalid(path, "a non-null value");
        if (expected == String.class) {
            string(value, path);
        } else if (expected == boolean.class) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                throw invalid(path, "a boolean");
            }
        } else if (expected == int.class) {
            integer(value, path);
        } else if (expected instanceof ParameterizedType listType && listType.getRawType() == List.class) {
            if (!value.isJsonArray()) throw invalid(path, "an array");
            int index = 0;
            for (JsonElement item : value.getAsJsonArray()) {
                validateValue(item, listType.getActualTypeArguments()[0], path + "[" + index++ + "]");
            }
        } else if (expected instanceof Class<?> model && model.isEnum()) {
            String name = string(value, path);
            boolean known = false;
            for (Object constant : model.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(name)) known = true;
            }
            if (!known) throw invalid(path, "a documented " + model.getSimpleName() + " value");
        } else if (expected instanceof Class<?> model && model.isRecord()) {
            if (!value.isJsonObject()) throw invalid(path, "an object");
            validateRecord(value.getAsJsonObject(), model, path);
        } else {
            throw new IllegalStateException("Unsupported model field type: " + expected);
        }
    }

    private static void validateText(String text, String name, Class<?> model, String path) throws ProtocolException {
        if (name.equals("id") || name.equals("clientId") || name.equals("alertId") || name.equals("requestId")) {
            if (!UUID_TEXT.matcher(text).matches()) throw invalid(path, "a UUID string");
        }
        if (name.equals("createdAt") || name.equals("acknowledgedAt")) {
            try {
                if (!OffsetDateTime.parse(text).getOffset().equals(ZoneOffset.UTC)) {
                    throw invalid(path, "an ISO-8601 UTC timestamp");
                }
            } catch (DateTimeParseException e) {
                throw invalid(path, "an ISO-8601 UTC timestamp");
            }
        }
        if (model == Messages.CreateAlert.class || model == Messages.Alert.class) {
            int maximum = name.equals("title") ? 120 : name.equals("message") ? 4000 : 0;
            if (maximum != 0 && (text.isBlank() || text.codePointCount(0, text.length()) > maximum)) {
                throw invalid(path, "non-blank text of 1 to " + maximum + " Unicode characters");
            }
        }
    }

    private static JsonElement required(JsonObject object, String name) throws ProtocolException {
        if (!object.has(name) || object.get(name).isJsonNull()) {
            throw new ProtocolException("Missing or null required field: " + name);
        }
        return object.get(name);
    }

    private static String string(JsonElement value, String path) throws ProtocolException {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid(path, "a string");
        return value.getAsString();
    }

    private static int integer(JsonElement value, String path) throws ProtocolException {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid(path, "an integer");
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw invalid(path, "an integer in Java's int range");
        }
    }

    private static ProtocolException invalid(String path, String expected) {
        return new ProtocolException("Invalid field " + path + "; expected " + expected + ".");
    }
}
