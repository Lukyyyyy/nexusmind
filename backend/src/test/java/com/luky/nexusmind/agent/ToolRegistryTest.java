package com.luky.nexusmind.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.agent.tool.AgentTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void registersAndExecutesOnlyKnownTools() throws Exception {
        AgentTool echo = new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("echo", "test", mapper.createObjectNode().put("type", "object"));
            }

            @Override
            public ToolResult execute(String callId, JsonNode arguments, AgentContext context) {
                var output = mapper.createObjectNode().put("value", arguments.path("value").asText());
                return new ToolResult(callId, "echo", output, true, 1);
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(echo), mapper);
        AgentContext context = new AgentContext("alice", 1L, "ws-1", "42");

        ToolResult success = registry.execute(
                new ToolCall("call-1", "echo", mapper.readTree("{\"value\":\"ok\"}"), "{\"value\":\"ok\"}"),
                context);
        ToolResult unknown = registry.execute(
                new ToolCall("call-2", "missing", mapper.createObjectNode(), "{}"), context);

        assertEquals(List.of("echo"), registry.definitions().stream().map(ToolDefinition::name).toList());
        assertTrue(success.success());
        assertEquals("ok", success.content().path("value").asText());
        assertFalse(unknown.success());
        assertEquals("UNKNOWN_TOOL", unknown.content().path("code").asText());
    }

    @Test
    void sourceAccessIsScopedToTheCurrentAgentRun() {
        AgentContext first = new AgentContext("alice", 1L, "ws-1", "42");
        AgentContext second = new AgentContext("alice", 1L, "ws-1", "42");

        first.allowSource("abc", 7);

        assertTrue(first.isSourceAllowed("abc", 7));
        assertFalse(first.isSourceAllowed("abc", 8));
        assertFalse(second.isSourceAllowed("abc", 7));
        assertEquals("kb:abc:7", AgentContext.sourceId("abc", 7));
    }

    @Test
    void repairsOnlyIncompleteSourcesWithRetrievedChunks() {
        AgentContext context = new AgentContext("alice", 1L, "ws-1", "42");
        String md5 = "850dfc7a9922a9d33297b18f1e3b7447";
        context.allowSource(md5, 24);
        context.allowSource(md5, 3);

        assertEquals("source kb:" + md5 + ":24", context.repairIncompleteSourceIds("source kb:" + md5));
        assertEquals("source kb:" + md5 + ":3", context.repairIncompleteSourceIds("source kb:" + md5 + ":3"));
    }
}
