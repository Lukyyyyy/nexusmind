package com.luky.nexusmind.agent;

import java.util.Map;

/** A user-visible execution summary. It intentionally excludes hidden chain-of-thought. */
public record AgentEvent(
        String type,
        String stepId,
        String category,
        String status,
        String title,
        String detail,
        String tool,
        Map<String, Object> input,
        Integer resultCount,
        Long durationMs) {

    public static AgentEvent thinking() {
        return new AgentEvent("agent_step", "thinking", "thinking", "running",
                "正在分析问题", "判断是否需要检索知识库，并选择合适的工具", null, null, null, null);
    }

    public static AgentEvent thinkingCompleted(boolean usesTools) {
        return new AgentEvent("agent_step", "thinking", "thinking", "completed",
                "已完成问题分析", usesTools ? "已选择适合当前问题的检索工具" : "当前问题无需调用工具，将直接组织回答",
                null, null, null, null);
    }

    public static AgentEvent toolStarted(ToolCall call) {
        return new AgentEvent("agent_step", call.id(), "tool", "running",
                title(call.name()), decision(call.name()), call.name(), safeInput(call), null, null);
    }

    public static AgentEvent toolCompleted(ToolCall call, int resultCount, long durationMs, boolean success) {
        return new AgentEvent("agent_step", call.id(), "tool", success ? "completed" : "error",
                title(call.name()), result(success, call.name(), resultCount), call.name(), safeInput(call),
                resultCount, durationMs);
    }

    public static AgentEvent answering() {
        return new AgentEvent("agent_step", "answering", "answer", "running",
                "正在整理答案", "正在分析检索结果并生成回答", null, null, null, null);
    }

    private static String title(String tool) {
        return switch (tool) {
            case "search_knowledge_base" -> "检索知识库";
            case "search_knowledge_graph" -> "查询知识图谱";
            case "get_chunk_context" -> "补充相邻内容";
            default -> "调用工具";
        };
    }

    private static String decision(String tool) {
        return switch (tool) {
            case "search_knowledge_base" -> "问题涉及知识库内容，正在查找相关文档片段";
            case "search_knowledge_graph" -> "问题涉及实体关系，正在查找跨文档关联路径";
            case "get_chunk_context" -> "现有片段上下文不完整，正在读取前后相邻内容";
            default -> "正在执行受控的只读工具";
        };
    }

    private static String result(boolean success, String tool, int count) {
        if (!success) return "工具执行未成功，模型将使用已有资料继续处理";
        return switch (tool) {
            case "search_knowledge_base" -> "找到 " + count + " 个相关文档片段";
            case "search_knowledge_graph" -> "找到 " + count + " 条关系路径";
            case "get_chunk_context" -> "补充了 " + count + " 个相邻片段";
            default -> "工具执行完成";
        };
    }

    private static Map<String, Object> safeInput(ToolCall call) {
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        switch (call.name()) {
            case "search_knowledge_base" -> {
                input.put("query", abbreviate(call.arguments().path("query").asText(""), 200));
            }
            case "search_knowledge_graph" -> {
                input.put("query", abbreviate(call.arguments().path("query").asText(""), 200));
            }
            case "get_chunk_context" -> {
                input.put("sourceId", AgentContext.sourceId(
                        call.arguments().path("fileMd5").asText(""), call.arguments().path("chunkId").asInt(-1)));
                input.put("before", call.arguments().path("before").asInt(1));
                input.put("after", call.arguments().path("after").asInt(1));
            }
            default -> { }
        }
        return input.isEmpty() ? null : Map.copyOf(input);
    }

    private static String abbreviate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
    }
}
