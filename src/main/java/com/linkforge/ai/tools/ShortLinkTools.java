package com.linkforge.ai.tools;

import com.linkforge.common.LoginUserContext;
import com.linkforge.dto.ShortLinkCreateDTO;
import com.linkforge.service.ShortLinkService;
import com.linkforge.vo.ShortLinkVO;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class ShortLinkTools {

    private final ShortLinkService shortLinkService;

    public ShortLinkTools(ShortLinkService shortLinkService) {
        this.shortLinkService = shortLinkService;
    }

    @Tool(description = "根据用户提供的完整网址和有效期天数创建短链接")
    public ShortLinkVO createShortLink(
            @ToolParam(description = "用户提供的完整HTTP或HTTPS网址")
            String originUrl,
            @ToolParam(description = "用户指定的有效期天数，必须至少为1")
            int validDays) {

        if (LoginUserContext.getUserId() == null) {
            throw new IllegalStateException("请先登录");
        }

        if (validDays < 1) {
            throw new IllegalArgumentException("有效期天数至少为1");
        }

        ShortLinkCreateDTO dto = new ShortLinkCreateDTO();
        dto.setOriginUrl(originUrl);
        dto.setValidDays(validDays);

        return shortLinkService.createShortLink(dto);
    }
}