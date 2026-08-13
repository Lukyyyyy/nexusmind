package com.luky.nexusmind.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.luky.nexusmind.agent.AgentContext;
import com.luky.nexusmind.agent.ToolDefinition;
import com.luky.nexusmind.agent.ToolResult;

public interface AgentTool {
    ToolDefinition definition();
    ToolResult execute(String callId, JsonNode arguments, AgentContext context);
}
