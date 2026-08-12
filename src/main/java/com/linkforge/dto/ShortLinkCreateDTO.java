package com.linkforge.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

@Data
public class ShortLinkCreateDTO {

    @NotBlank(message = "原始长链接不能为空")
    @URL(message = "长链接格式不正确，必须包含 http:// 或 https://")
    private String originUrl;
}