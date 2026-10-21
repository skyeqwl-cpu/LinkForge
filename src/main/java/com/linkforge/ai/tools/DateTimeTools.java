package com.linkforge.ai.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class DateTimeTools {

    @Tool(description = "查询当前的北京时间，返回日期、时间和时区")
    public String getCurrentTime() {
        return ZonedDateTime.now(
                ZoneId.of("Asia/Shanghai")
        ).toString();
    }
    @Tool(description = "查询从北京时间今天起，指定天数之后的日期")
    public String getDateAfterDays(
            @ToolParam(description = "从今天起经过的天数，例如三天后传3")
            int days) {

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate targetDate = today.plusDays(days);

        return targetDate.toString();
    }
}
