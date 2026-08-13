package com.linkforge.common;

import lombok.Data;

import java.util.List;

/**
 * 通用分页返回结构
 * 对应：{ records: [], total: 100, page: 1, size: 10 }
 */
@Data
public class PageResult<T> {

    /**
     * 当前页数据
     */
    private List<T> records;

    /**
     * 总记录数
     */
    private long total;

    /**
     * 当前页码（从 1 开始）
     */
    private long page;

    /**
     * 每页大小
     */
    private long size;
}
