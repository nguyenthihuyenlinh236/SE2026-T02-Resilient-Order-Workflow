package com.example.orderworkflow.order.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.orderworkflow.order.api.OrderDraftDtos.DraftItem;
import com.example.orderworkflow.order.api.OrderDraftDtos.DraftResponse;
import com.example.orderworkflow.order.api.OrderDraftDtos.SaveDraftRequest;
import com.example.orderworkflow.order.repository.OrderDraftRepository;
import com.example.orderworkflow.order.repository.OrderDraftRepository.StoredDraft;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
public class OrderDraftService {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private final OrderDraftRepository repository;
    private final ObjectMapper json;

    public OrderDraftService(OrderDraftRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    @Transactional
    public DraftResponse save(String clientId, String draftId, String correlationId,
            SaveDraftRequest request) {
        validateId(clientId, "X-Client-Id");
        validateId(draftId, "draftId");
        String effectiveCorrelation = validCorrelation(correlationId)
                ? correlationId : UUID.randomUUID().toString();
        String payload = payload(request);
        String hash = sha256(payload);

        var written = repository.saveNewer(clientId, draftId, request.revision(), payload, hash,
                effectiveCorrelation);
        if (written.isPresent()) {
            return response(written.get(), written.get().inserted() ? "CREATED" : "UPDATED");
        }

        StoredDraft current = repository.find(clientId, draftId)
                .orElseThrow(() -> new IllegalStateException("Conditional draft write lost its row"));
        if (request.revision() < current.revision()) {
            throw new OrderException(409, "STALE_DRAFT_REVISION",
                    "A newer draft revision has already been saved");
        }
        if (request.revision() == current.revision() && hash.equals(current.payloadHash())) {
            return response(current, "REPLAYED");
        }
        throw new OrderException(409, "DRAFT_REVISION_CONFLICT",
                "The revision is already associated with a different draft snapshot");
    }

    @Transactional(readOnly = true)
    public DraftResponse get(String clientId, String draftId) {
        validateId(clientId, "X-Client-Id");
        validateId(draftId, "draftId");
        return repository.find(clientId, draftId)
                .map(value -> response(value, null))
                .orElseThrow(() -> new OrderException(404, "DRAFT_NOT_FOUND", "Draft not found"));
    }

    private String payload(SaveDraftRequest request) {
        ObjectNode node = json.createObjectNode();
        node.put("customerId", request.customerId());
        node.put("storeId", request.storeId());
        node.set("items", json.valueToTree(request.items() == null ? List.of() : request.items()));
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize draft snapshot", exception);
        }
    }

    private DraftResponse response(StoredDraft stored, String outcome) {
        try {
            JsonNode payload = json.readTree(stored.payload());
            List<DraftItem> items = json.readerForListOf(DraftItem.class).readValue(payload.get("items"));
            return new DraftResponse(stored.draftId(), stored.revision(), text(payload, "customerId"),
                    text(payload, "storeId"), items, outcome, stored.savedAt(), stored.correlationId());
        } catch (IOException exception) {
            throw new IllegalStateException("Stored draft snapshot is invalid", exception);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static void validateId(String value, String field) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new OrderException(400, "VALIDATION_ERROR", field + " is invalid");
        }
    }

    private static boolean validCorrelation(String value) {
        return value != null && !value.isBlank() && value.length() <= 128;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
