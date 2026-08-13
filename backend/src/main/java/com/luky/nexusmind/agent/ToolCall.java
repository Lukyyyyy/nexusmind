package com.luky.nexusmind.agent;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolCall(String id, String name, JsonNode arguments, String rawArguments) {
}
