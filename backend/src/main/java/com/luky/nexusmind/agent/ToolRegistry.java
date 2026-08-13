package com.luky.nexusmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luky.nexusmind.agent.tool.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private final Map<String, AgentTool> tools;
    private final ObjectMapper objectMapper;

    public ToolRegistry(List<AgentTool> agentTools, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        Map<String, AgentTool> registered = new LinkedHashMap<>();
        for (AgentTool tool : agentTools) registered.put(tool.definition().name(), tool);
        this.tools = Map.copyOf(registered);
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(AgentTool::definition).toList();
    }

    public ToolResult execute(ToolCall call, AgentContext context) {
        AgentTool tool = tools.get(call.name());
        if (tool == null) return error(call, "UNKNOWN_TOOL", "不支持的工具：" + call.name());
        try {
            return tool.execute(call.id(), call.arguments(), context);
        } catch (IllegalArgumentException e) {
            return error(call, "INVALID_ARGUMENTS", e.getMessage());
        } catch (Exception e) {
            log.warn("Agent 工具执行失败: tool={}, callId={}", call.name(), call.id(), e);
            return error(call, "TOOL_FAILED", "工具暂时不可用");
        }
    }

    private ToolResult error(ToolCall call, String code, String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("status", "error");
        node.put("code", code);
        node.put("message", message);
        return new ToolResult(call.id(), call.name(), node, false, 0);
    }
}
