package com.luky.nexusmind.client;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeepSeekClientTest {

    @Test
    void collectTitleIgnoresReasoningAndJoinsStreamedContent() {
        DeepSeekClient client = new DeepSeekClient(null, null, null);

        String title = client.collectTitle(Flux.just(
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"分析中\",\"content\":\"\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"产品需求\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"风险分析\"}}]}",
                "[DONE]"));

        assertEquals("产品需求风险分析", title);
    }
}
