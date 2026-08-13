package com.linkforge.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 自动填充 createTime，不再依赖 MySQL 的 DEFAULT CURRENT_TIMESTAMP。
 *
 * 实体字段需标注 @TableField(fill = FieldFill.INSERT) 才会被填充。
 * 仅 INSERT 时填充，保持「创建时间」语义不变（UPDATE 不重写）。
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        // Supplier 版本：strict 模式下仅当字段为 null 且标注了 fill=INSERT 才填充
        this.strictInsertFill(
                metaObject,
                "createTime",
                LocalDateTime.class,
                LocalDateTime.now()
        );
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        // 当前实体无 updateTime 字段，且 createTime 不应在更新时被改写，留空即可
    }
}
