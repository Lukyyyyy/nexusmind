package com.luky.nexusmind.agent;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolResult(String callId, String toolName, JsonNode content, boolean success, int resultCount) {
}
