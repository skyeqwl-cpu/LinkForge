package com.linkforge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkforge.dto.ShortLinkCreateDTO;
import com.linkforge.entity.ShortLink;
import com.linkforge.exception.BizException;
import com.linkforge.mapper.ShortLinkMapper;
import com.linkforge.utils.Base62Utils;
import com.linkforge.vo.ShortLinkVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShortLinkService {
    @Autowired
    private ShortLinkMapper shortLinkMapper;

    // 1. 生成短链接
    @Transactional
    public ShortLinkVO createShortLink(ShortLinkCreateDTO dto) {
        // 先插入一条只有 originUrl 的数据，获取自增 ID
        ShortLink shortLink = new ShortLink();
        shortLink.setOriginUrl(dto.getOriginUrl());
        shortLinkMapper.insert(shortLink);

        // 根据自增 ID 转成 Base62 短码
        String shortCode = Base62Utils.encode(shortLink.getId());

        // 更新 short_code 回数据库
        shortLink.setShortCode(shortCode);
        shortLinkMapper.updateById(shortLink);

        //return shortCode;
        String domain = "http://localhost:8081/";
        return ShortLinkVO.builder()
                .shortCode(shortCode)
                .shortUrl(domain + shortCode)
                .originUrl(dto.getOriginUrl())
                .build();
    }

    // 2. 根据短码获取长网址
    public String getOriginUrl(String shortCode) {
        LambdaQueryWrapper<ShortLink> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ShortLink::getShortCode, shortCode);
        ShortLink shortLink = shortLinkMapper.selectOne(wrapper);

        if (shortLink == null) {
            throw new BizException(404, "短链接不存在或已失效");
        }
        return shortLink.getOriginUrl();
    }
}
