package com.example.monitoring.lifecycle.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class LifecyclePolicyContract {

    private LifecyclePolicyContract() {
    }

    public static JsonNode defaultPolicy(ObjectMapper objectMapper) throws JsonProcessingException {
        return objectMapper.readTree(new LifecycleEventCodec(objectMapper).defaultPolicyJson());
    }
}
