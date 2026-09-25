package com.linkforge.entity;

/**
 * 短链接访问类型。
 *
 * 数据库用 Integer 存 {0,1}，本枚举只做语义转换与校验，
 * 不作为 MyBatis-Plus 实体字段类型（避免枚举映射配置坑）。
 */
public enum AccessType {

    PUBLIC(0, "无限制（任何人可访问）"),
    PRIVATE(1, "仅创建者（owner）可访问");

    private final int code;
    private final String desc;


    AccessType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /** 按 code 还原枚举，null 或未知值一律回落为 PUBLIC（无限制）。 */
    public static AccessType of(Integer code) {
        if (code == null) {
            return PUBLIC;
        }
        for (AccessType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return PUBLIC;
    }

    public boolean isPrivate() {
        return this == PRIVATE;
    }
}
