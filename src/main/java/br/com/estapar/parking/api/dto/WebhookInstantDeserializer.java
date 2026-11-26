package br.com.estapar.parking.api.dto;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

public class WebhookInstantDeserializer extends ValueDeserializer<Instant> {

    @Override
    public Instant deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return (Instant) context.handleUnexpectedToken(Instant.class, parser);
        }

        String value = parser.getValueAsString().trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException instantException) {
            try {
                return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException localDateTimeException) {
                throw context.weirdStringException(
                        value,
                        Instant.class,
                        "Expected an ISO-8601 instant or the simulator's UTC date-time without an offset"
                );
            }
        }
    }
}
