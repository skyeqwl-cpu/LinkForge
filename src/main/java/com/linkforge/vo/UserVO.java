package com.linkforge.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class UserVO {

    private Long id;

    private String username;

    private LocalDateTime createTime;
}
