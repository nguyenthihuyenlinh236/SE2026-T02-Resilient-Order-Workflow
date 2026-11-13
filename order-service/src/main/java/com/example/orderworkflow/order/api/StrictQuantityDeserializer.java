package com.example.orderworkflow.order.api;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

public final class StrictQuantityDeserializer extends JsonDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            throw JsonMappingException.from(parser, "quantity must be an integer");
        }
        try {
            return parser.getIntValue();
        } catch (Exception exception) {
            throw JsonMappingException.from(parser, "quantity is outside the supported integer range", exception);
        }
    }
}
