package com.linkforge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkforge.entity.ShortLink;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ShortLinkMapper extends BaseMapper<ShortLink> {
}
