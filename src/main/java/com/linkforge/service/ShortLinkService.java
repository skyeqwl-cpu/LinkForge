package com.linkforge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkforge.dto.ShortLinkCreateDTO;
import com.linkforge.entity.ShortLink;
import com.linkforge.exception.BizException;
import com.linkforge.mapper.ShortLinkMapper;
import com.linkforge.utils.Base62Utils;
import com.linkforge.vo.ShortLinkVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

@Service
public class ShortLinkService {
    @Autowired
    private ShortLinkMapper shortLinkMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String SHORT_LINK_KEY_PREFIX = "shortLink";//短链接缓存前缀

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

        // 生成时顺便预热写入 Redis 缓存（缓存 7 天）
        String redisKey = SHORT_LINK_KEY_PREFIX + shortCode;//短链接缓存键
        // 缓存 7 天, 值为 originUrl
        stringRedisTemplate.opsForValue().set(redisKey, dto.getOriginUrl(), 7, TimeUnit.DAYS);

        //return shortCode;
        String domain = "http://localhost:8081/";
        return ShortLinkVO.builder()
                .shortCode(shortCode)
                .shortUrl(domain + shortCode)
                .originUrl(dto.getOriginUrl())
                .build();
    }

    // 2. 根据短码获取长网址
    /*public String getOriginUrl(String shortCode) {
        LambdaQueryWrapper<ShortLink> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ShortLink::getShortCode, shortCode);
        ShortLink shortLink = shortLinkMapper.selectOne(wrapper);

        if (shortLink == null) {
            throw new BizException(404, "短链接不存在或已失效");
        }
        return shortLink.getOriginUrl();
    }*/
    public String getOriginUrl(String shortCode) {

        // 1. Redis Key
        String redisKey = SHORT_LINK_KEY_PREFIX + shortCode;

        // 2. 查询 Redis
        String originUrl = stringRedisTemplate
                .opsForValue()
                .get(redisKey);

        // 3. Redis 命中
        if (StringUtils.hasText(originUrl)) {
            System.out.println("====== Redis 命中 ======");
            System.out.println("shortCode: " + shortCode);
            System.out.println("originUrl: " + originUrl);
            return originUrl;
        }

        //Redis 未命中，查询 MySQL
        System.out.println("====== Redis 未命中，查询 MySQL ======");
        System.out.println("shortCode: " + shortCode);

        // 4. Redis 未命中，查询 MySQL
        LambdaQueryWrapper<ShortLink> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ShortLink::getShortCode, shortCode);

        ShortLink shortLink = shortLinkMapper.selectOne(wrapper);

        // 5. MySQL 也不存在
        if (shortLink == null) {
            throw new BizException(404, "短链接不存在或已失效");
        }

        // 6. 回写 Redis
        stringRedisTemplate.opsForValue()
                .set(
                        redisKey,
                        shortLink.getOriginUrl(),
                        7,
                        TimeUnit.DAYS
                );

        // 7. 返回原始 URL
        return shortLink.getOriginUrl();
    }

}
