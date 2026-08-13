package com.luky.nexusmind.agent;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolDefinition(String name, String description, JsonNode parameters) {
}
