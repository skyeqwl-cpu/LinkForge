package com.linkforge.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

@Data
public class ShortLinkCreateDTO {

    @NotBlank(message = "原始长链接不能为空")
    @Pattern(regexp = "^https?://.+", message = "长链接格式不正确，必须为 http(s)://域名/... 形式")
    private String originUrl;

    /**
     * 有效期天数（可选）。默认 7 天。
     * 采用「validDays」而非让用户自己算 expireTime 时间点，
     * 用户无需自行计算过期时刻，后端统一 now + validDays 计算。
     */
    @Min(value = 1, message = "有效期天数至少为 1 天")
    private Integer validDays;

    /**
     * 访问限制类型（可选）：
     * 0 = 无限制，任何人可访问（默认，保留原有公开行为）；
     * 1 = 仅创建者（owner）可访问，其他人需登录且非 owner 将被拒绝。
     * 不传则视为 0（无限制）。
     */
    private Integer accessType;
}