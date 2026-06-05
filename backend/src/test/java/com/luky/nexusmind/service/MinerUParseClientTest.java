package com.luky.nexusmind.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MinerUParseClientTest {

    private MinerUParseClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        client = new MinerUParseClient();
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        server = MockRestServiceServer.bindTo(restTemplate).build();

        ReflectionTestUtils.setField(client, "baseUrl", "http://mineru.test/");
        ReflectionTestUtils.setField(client, "parsePath", "/file_parse");
        ReflectionTestUtils.setField(client, "parseMethod", "auto");
        ReflectionTestUtils.setField(client, "parseBackend", "hybrid-auto-engine");
        ReflectionTestUtils.setField(client, "ocr", false);
        ReflectionTestUtils.setField(client, "enableTable", false);
        ReflectionTestUtils.setField(client, "enableFormula", false);
    }

    @Test
    void parseToTextSendsConfiguredBackend() throws IOException {
        server.expect(requestTo("http://mineru.test/file_parse"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("name=\"backend\"")))
                .andExpect(content().string(containsString("hybrid-auto-engine")))
                .andRespond(withSuccess("{\"results\":[{\"md_content\":\"hello\"}]}",
                        MediaType.APPLICATION_JSON));

        assertEquals("hello", client.parseToText("%PDF".getBytes(), "test.pdf"));
        server.verify();
    }

    @Test
    void parseToTextIncludesMinerUErrorBody() {
        server.expect(requestTo("http://mineru.test/file_parse"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"model download failed\"}"));

        IOException exception = assertThrows(IOException.class,
                () -> client.parseToText("%PDF".getBytes(), "test.pdf"));

        assertEquals("MinerU parse service returned 502 BAD_GATEWAY: {\"detail\":\"model download failed\"}",
                exception.getMessage());
        server.verify();
    }
}
