package com.linkforge.ai.controller;

import com.linkforge.ai.tools.ShortLinkTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ShortLinkAiController {

    private final ChatClient chatClient;
    private final ShortLinkTools shortLinkTools;

    public ShortLinkAiController(
            ChatClient.Builder builder,
            ShortLinkTools shortLinkTools) {

        this.chatClient = builder.build();
        this.shortLinkTools = shortLinkTools;
    }

    @PostMapping(
            value = "/api/ai/chat",
            consumes = "text/plain",
            produces = "text/plain;charset=UTF-8"
    )
    public String chat(@RequestBody String message) {
        return chatClient.prompt()
                .system("""
                        你是短链接助手。
                        创建短链接前必须获得用户提供的完整网址和有效期天数。
                        缺少信息时先询问，不要猜测。
                        只有工具成功返回后才能表示创建成功。
                        回答中的短链接和过期时间必须使用工具返回的数据。
                        """)
                .user(message)
                .tools(shortLinkTools)
                .call()
                .content();
    }
}