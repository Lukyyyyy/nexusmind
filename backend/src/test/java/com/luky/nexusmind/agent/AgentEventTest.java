package com.luky.nexusmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentEventTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void toolEventExposesOnlySafeSearchSummary() throws Exception {
        ToolCall call = new ToolCall("call-1", "search_knowledge_base",
                mapper.readTree("{\"query\":\"退款审批流程\",\"topK\":6,\"userId\":\"forged\"}"), "{}");

        AgentEvent event = AgentEvent.toolStarted(call);

        assertEquals("检索知识库", event.title());
        assertEquals("退款审批流程", event.input().get("query"));
        assertFalse(event.input().containsKey("topK"));
        assertFalse(event.input().containsKey("userId"));
    }

    @Test
    void completedEventContainsResultCountAndDuration() throws Exception {
        ToolCall call = new ToolCall("call-2", "search_knowledge_graph",
                mapper.readTree("{\"query\":\"系统依赖\"}"), "{}");

        AgentEvent event = AgentEvent.toolCompleted(call, 3, 42, true);

        assertEquals("completed", event.status());
        assertEquals(3, event.resultCount());
        assertEquals(42L, event.durationMs());
        assertEquals("找到 3 条关系路径", event.detail());
    }
}
