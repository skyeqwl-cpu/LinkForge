package com.linkforge.runner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkforge.entity.ShortLink;
import com.linkforge.mapper.ShortLinkMapper;
import org.redisson.api.RBloomFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

//项目启动（runner/Bloom（runner/BloomFilterWarmupRunner.java）
//项目启动时，把 MySQL 已有的短码装载进布隆过滤器：
@Component
public class BloomFilterWarmupRunner implements CommandLineRunner {

    @Autowired
    private ShortLinkMapper shortLinkMapper;

    @Autowired
    private RBloomFilter<String> shortLinkBloomFilter;

    @Override
    public void run(String... args) {
        System.out.println("====== 开始预热布隆过滤器 ======");
        LambdaQueryWrapper<ShortLink> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(ShortLink::getShortCode);
        List<ShortLink> list = shortLinkMapper.selectList(wrapper);

        int count = 0;
        for (ShortLink item : list) {
            if (item.getShortCode() != null) {
                shortLinkBloomFilter.add(item.getShortCode());
                count++;
            }
        }
        System.out.println("====== 布隆过滤器预热完成，共加载 " + count + " 条短码 ======");
    }
}