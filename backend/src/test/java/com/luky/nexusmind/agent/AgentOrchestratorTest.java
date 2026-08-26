package com.luky.nexusmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.client.DeepSeekClient;
import com.luky.nexusmind.client.GenerationCancellation;
import com.luky.nexusmind.agent.tool.AgentTool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOrchestratorTest {

    @Test
    void answeringStepComesAfterAllToolRounds() {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolCall firstCall = new ToolCall(
                "call-1", "search_knowledge_base", objectMapper.createObjectNode().put("query", "文档列表"), "{}");
        ToolCall secondCall = new ToolCall(
                "call-2", "search_knowledge_graph", objectMapper.createObjectNode().put("query", "文档关系"), "{}");
        StubDeepSeekClient client = new StubDeepSeekClient(List.of(
                new DeepSeekClient.AgentDecision(objectMapper.createObjectNode().put("role", "assistant"), List.of(firstCall)),
                new DeepSeekClient.AgentDecision(objectMapper.createObjectNode().put("role", "assistant"), List.of(secondCall)),
                new DeepSeekClient.AgentDecision(objectMapper.createObjectNode()
                        .put("role", "assistant").put("content", "最终回答"), List.of())));
        ToolRegistry tools = new ToolRegistry(List.of(), objectMapper);

        AgentOrchestrator orchestrator = new AgentOrchestrator(client, tools, objectMapper, true, 3, 3);
        List<AgentEvent> events = new ArrayList<>();
        List<String> chunks = new ArrayList<>();
        orchestrator.run("alice", "有哪些文档？", List.of(),
                new AgentContext("alice", 1L, "ws-1", "1", List.of(), List.of()),
                events::add, chunks::add, error -> { }, () -> { });

        assertEquals(List.of("thinking", "thinking", "call-1", "call-1", "call-2", "call-2", "answering"),
                events.stream().map(AgentEvent::stepId).toList());
        assertEquals(List.of("最终回答"), chunks);
        assertTrue(client.streamCalled);
        assertFalse(client.firstSystemPrompt.contains("企业制度"));
        assertTrue(client.firstSystemPrompt.contains("不得凭空枚举"));
    }

    @Test
    void rejectsToolCallsRenderedAsAssistantText() {
        ObjectMapper objectMapper = new ObjectMapper();
        StubDeepSeekClient client = new StubDeepSeekClient(List.of(new DeepSeekClient.AgentDecision(
                objectMapper.createObjectNode()
                        .put("role", "assistant")
                        .put("content", "我将调用 search_knowledge_base 为您查询。"),
                List.of())));
        AgentTool searchTool = new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("search_knowledge_base", "search", objectMapper.createObjectNode());
            }

            @Override
            public ToolResult execute(String callId, com.fasterxml.jackson.databind.JsonNode arguments,
                                      AgentContext context) {
                throw new AssertionError("must not execute textual tool calls");
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                client, new ToolRegistry(List.of(searchTool), objectMapper), objectMapper, true, 3, 3);

        assertThrows(IllegalStateException.class, () -> orchestrator.run(
                "alice", "深圳今天天气怎么样？", List.of(),
                new AgentContext("alice", 1L, "ws-1", "1", List.of(), List.of()),
                event -> { }, chunk -> { }, error -> { }, () -> { }));
        assertFalse(client.streamCalled);
    }

    @Test
    void rejectsTextualToolProtocolSplitAcrossFinalStreamChunks() {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolCall call = new ToolCall(
                "call-1", "search_knowledge_base", objectMapper.createObjectNode().put("query", "核心内容"), "{}");
        StubDeepSeekClient client = new StubDeepSeekClient(
                List.of(new DeepSeekClient.AgentDecision(
                        objectMapper.createObjectNode().put("role", "assistant"), List.of(call))),
                List.of("<|DS", "ML|tool_calls>"));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                client, new ToolRegistry(List.of(), objectMapper), objectMapper, true, 1, 3);
        List<AgentEvent> events = new ArrayList<>();
        List<String> chunks = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        boolean[] completed = { false };

        orchestrator.run("alice", "总结知识库", List.of(),
                new AgentContext("alice", 1L, "ws-1", "1", List.of(), List.of()),
                events::add, chunks::add, errors::add, () -> completed[0] = true);

        assertTrue(client.streamCalled);
        assertEquals("answering", events.get(events.size() - 1).stepId());
        assertTrue(chunks.isEmpty());
        assertEquals(1, errors.size());
        assertFalse(completed[0]);
    }

    @Test
    void stopsWhenAnotherRoundAddsNoSources() {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolCall firstCall = new ToolCall(
                "call-1", "search_knowledge_base", objectMapper.createObjectNode().put("query", "声音事件检测"),
                "{\"query\":\"声音事件检测\"}");
        ToolCall secondCall = new ToolCall(
                "call-2", "search_knowledge_base", objectMapper.createObjectNode().put("query", "声音事件检测方法"),
                "{\"query\":\"声音事件检测方法\"}");
        StubDeepSeekClient client = new StubDeepSeekClient(List.of(
                new DeepSeekClient.AgentDecision(objectMapper.createObjectNode().put("role", "assistant"), List.of(firstCall)),
                new DeepSeekClient.AgentDecision(objectMapper.createObjectNode().put("role", "assistant"), List.of(secondCall)),
                new DeepSeekClient.AgentDecision(objectMapper.createObjectNode().put("role", "assistant"), List.of())));
        AgentTool tool = new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("search_knowledge_base", "search", objectMapper.createObjectNode());
            }

            @Override
            public ToolResult execute(String callId, com.fasterxml.jackson.databind.JsonNode arguments,
                                      AgentContext context) {
                context.allowSource("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 1);
                return new ToolResult(callId, definition().name(), objectMapper.createObjectNode(), true, 1);
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                client, new ToolRegistry(List.of(tool), objectMapper), objectMapper, true, 3, 3);

        orchestrator.run("alice", "总结知识库", List.of(),
                new AgentContext("alice", 1L, "ws-1", "1", List.of(), List.of()),
                event -> { }, chunk -> { }, error -> { }, () -> { });

        assertEquals(2, client.decisionIndex);
        assertTrue(client.streamCalled);
    }

    @Test
    void cancellationStopsBeforeModelOrToolsRun() {
        ObjectMapper objectMapper = new ObjectMapper();
        StubDeepSeekClient client = new StubDeepSeekClient(List.of());
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                client, new ToolRegistry(List.of(), objectMapper), objectMapper, true, 3, 3);
        GenerationCancellation cancellation = new GenerationCancellation();
        cancellation.cancel();
        boolean[] completed = { false };

        orchestrator.run("alice", "总结知识库", List.of(),
                new AgentContext("alice", 1L, "ws-1", "1", List.of(), List.of()), cancellation,
                event -> { }, chunk -> { }, error -> { }, () -> completed[0] = true);

        assertEquals(0, client.decisionIndex);
        assertFalse(client.streamCalled);
        assertTrue(completed[0]);
    }

    private static final class StubDeepSeekClient extends DeepSeekClient {
        private final List<AgentDecision> decisions;
        private final List<String> streamChunks;
        private int decisionIndex;
        private boolean streamCalled;
        private String firstSystemPrompt;

        private StubDeepSeekClient(List<AgentDecision> decisions) {
            this(decisions, List.of("最终回答"));
        }

        private StubDeepSeekClient(List<AgentDecision> decisions, List<String> streamChunks) {
            super(null, null, null);
            this.decisions = decisions;
            this.streamChunks = streamChunks;
        }

        @Override
        public AgentDecision callWithTools(String configUsername,
                                           List<Map<String, Object>> messages,
                                           List<ToolDefinition> tools,
                                           String userId,
                                           String sessionId,
                                           String conversationId,
                                           GenerationCancellation cancellation) {
            if (firstSystemPrompt == null) firstSystemPrompt = String.valueOf(messages.get(0).get("content"));
            return decisions.get(decisionIndex++);
        }

        @Override
        public void streamAgentResponse(String configUsername,
                                        List<Map<String, Object>> messages,
                                        List<ToolDefinition> tools,
                                        String userId,
                                        String sessionId,
                                        String conversationId,
                                        GenerationCancellation cancellation,
                                        Consumer<String> onChunk,
                                        Consumer<Throwable> onError,
                                        Runnable onComplete) {
            streamCalled = true;
            streamChunks.forEach(onChunk);
            onComplete.run();
        }
    }
}
