package org.texttechnologylab.DockerUnifiedUIMAInterface.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.texttechnologylab.DockerUnifiedUIMAInterface.DUUIComposer;

/**
 * Parses the {@code DUUI-Logs} response header a tool component returns
 * <p>
 * The header value is a JSON array of records emitted by the tool's {@code duui_logging}
 * library, each shaped like:
 *
 * <pre>{@code { "level": "INFO", "message": "…", "logger": "…", "stacktrace": null } }</pre>
 *
 * Correlation (which component, which document) is added here on the Java side, since the
 * driver already knows both — the tool does not send them.
 */
public final class DUUIComponentLog {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DUUIComponentLog() {
    }

    /**
     * Parse a {@code DUUI-Logs} header value and emit each record to the composer.
     *
     * @param composer     the composer whose sink records are forwarded to
     * @param componentKey the component that produced the logs (for tagging)
     * @param documentId   the document being processed (for tagging), may be {@code null}
     * @param logsJson     the raw {@code DUUI-Logs} header value (a JSON array)
     */
    public static void emit(DUUIComposer composer, String componentKey, String documentId, String logsJson) {
        if (composer == null || logsJson == null || logsJson.isEmpty()) {
            return;
        }
        try {
            JsonNode array = MAPPER.readTree(logsJson);
            if (array == null || !array.isArray()) {
                return;
            }
            for (JsonNode node : array) {
                String level = text(node, "level", "INFO");
                String message = text(node, "message", "");
                String logger = text(node, "logger", null);
                String stacktrace = text(node, "stacktrace", null);
                Long timestamp = longOrNull(node, "timestamp");

                StringBuilder sb = new StringBuilder();
                if (composer.isLoggingSeverityEnabled()) {
                    sb.append('[').append(mapLevel(level).name()).append(']');
                }
                if (composer.isLoggingSourceEnabled()) {
                    boolean hasKey = componentKey != null && !componentKey.isBlank();
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append('[');
                    if (hasKey) {
                        sb.append(componentKey);
                    }
                    if (documentId != null) {
                        if (hasKey) {
                            sb.append(" | ");
                        }
                        sb.append(documentId);
                    }
                    sb.append("]");
                }
                if (logger != null && !logger.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(logger);
                }
                if (sb.length() > 0) {
                    sb.append(": ");
                }
                sb.append(message);
                if (stacktrace != null && !stacktrace.isEmpty()) {
                    sb.append(System.lineSeparator()).append(stacktrace);
                }

                if (timestamp != null) {
                    composer.addEvent(DUUIEvent.Sender.COMPONENT, sb.toString(), mapLevel(level), timestamp);
                } else {
                    composer.addEvent(DUUIEvent.Sender.COMPONENT, sb.toString(), mapLevel(level));
                }
            }
        } catch (Exception e) {
            composer.addEvent(
                    DUUIEvent.Sender.SYSTEM,
                    "[ComponentLog] failed to parse component logs: " + e.getMessage(),
                    DUUIComposer.DebugLevel.DEBUG);
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? fallback : value.asText();
    }

    /**
     * Read a numeric field as a {@code Long}, returning {@code null} when the field is absent,
     * JSON null, or not a parsable number.
     */
    private static Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        try {
            return Long.parseLong(value.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Map a Python-style log level name to a DUUI {@link DUUIComposer.DebugLevel}.
     *
     * @param level the level name (case-insensitive; {@code WARNING}/{@code FATAL} accepted)
     * @return the matching debug level, defaulting to {@link DUUIComposer.DebugLevel#INFO}
     */
    public static DUUIComposer.DebugLevel mapLevel(String level) {
        if (level == null) {
            return DUUIComposer.DebugLevel.INFO;
        }
        switch (level.trim().toUpperCase()) {
            case "TRACE":
                return DUUIComposer.DebugLevel.TRACE;
            case "DEBUG":
                return DUUIComposer.DebugLevel.DEBUG;
            case "INFO":
            case "INFORMATION":
                return DUUIComposer.DebugLevel.INFO;
            case "WARN":
            case "WARNING":
                return DUUIComposer.DebugLevel.WARN;
            case "ERROR":
                return DUUIComposer.DebugLevel.ERROR;
            case "CRITICAL":
            case "FATAL":
                return DUUIComposer.DebugLevel.CRITICAL;
            default:
                return DUUIComposer.DebugLevel.INFO;
        }
    }
}
