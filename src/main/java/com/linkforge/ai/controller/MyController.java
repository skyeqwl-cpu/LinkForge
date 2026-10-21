package com.linkforge.ai.controller;

import com.linkforge.ai.tools.DateTimeTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
class MyController {

    private final ChatClient chatClient;

    public MyController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @GetMapping("/ai/chat")
    String generation(@RequestParam("userInput") String userInput) {
        return this.chatClient.prompt()
                .user(userInput)
                .tools(new DateTimeTools())
                .call()
                .content();
    }
    @GetMapping(value = "/ai/sse", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> generationSse(
            @RequestParam("userInput") String userInput) {

        return this.chatClient.prompt()
                .user(userInput)
                .stream()
                .content();
    }
}