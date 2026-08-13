package com.linkforge.controller;

import com.linkforge.common.PageResult;
import com.linkforge.common.Result;
import com.linkforge.dto.ShortLinkCreateDTO;
import com.linkforge.service.ShortLinkService;
import com.linkforge.utils.Base62Utils;
import com.linkforge.vo.ShortLinkDetailVO;
import com.linkforge.vo.ShortLinkManageVO;
import com.linkforge.vo.ShortLinkStatsVO;
import com.linkforge.vo.ShortLinkVO;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

//RestController是@Controllet 和 @ResponseBody 的组合注解
/*在传统 Spring MVC 中，
@Controller 返回的字符串会被当成 HTML 页面路径（比如返回 "index" 会去找 index.html）。

而在前后端分离架构中，后端只需返回纯数据（字符串、JSON、XML）。
@RestController 告诉 Spring Boot：
这个类里所有方法的返回值，都直接作为 HTTP 响应体（Response Body）输出给客户端，不要试图找 HTML 页面。*/
@RestController
public class ShortLinkController {

    @Autowired
    private ShortLinkService shortLinkService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

   /* //生成短链接接口（模拟）
    @PostMapping ("/api/v1/short-link")
    public String create(@RequestParam String originUrl){
        *//*RequestParam用于获取 HTTP 请求中的查询参数（Query Parameter）或 表单数据（Form Data）。
        *当前端发送 POST 请求 http://localhost:8081/api/v1/short-link?originUrl=[https://baidu.com](https://baidu.com)，
        * 或者用表单提交 originUrl 时，这个参数跟在 ? 后面。
          需要用 @RequestParam 把 URL 中的 originUrl 的值注入到 Java 方法的形参变量里

        * *//*

        //TODO：后面在这里对接DB，先模拟ID测试
        *//*long mockId = 1000000L;
        String shortCode = Base62Utils.encode(mockId);*//*

        String shortCode = shortLinkService.createShortLink(originUrl);
        return "http://localhost:8081/"+shortCode;

    }*/
   // 1. 生成短链接（使用规范的 JSON Body + @Valid）
   @PostMapping("/api/v1/short-link")
   public Result<ShortLinkVO> createShortLink(@Valid @RequestBody ShortLinkCreateDTO dto) {
       ShortLinkVO vo = shortLinkService.createShortLink(dto);
       return Result.success(vo);
   }

    //核心 302 重定向跳转接口
    @GetMapping("/{shortCode:[0-9a-zA-Z]+}")
    public void redirect(@PathVariable String shortCode, HttpServletResponse response)throws IOException {
       /* //TODD: 后面从Redis/Mysql里面根据shortCode 查出 originUrl
        String targetUrl = "https://www.baidu.com"; // 假数据测试

        //设置 302 状态码和 Location 响应头
        response.setStatus(HttpServletResponse.SC_MOVED_TEMPORARILY);
        response.setHeader("Location", targetUrl);*/

        String originUrl = shortLinkService.getOriginUrl(shortCode);
        if (originUrl != null) {
            response.setStatus(HttpServletResponse.SC_MOVED_TEMPORARILY);
            response.setHeader("Location", originUrl);
        } else {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "短链接不存在或已失效");
        }
    }
    @GetMapping("/api/v1/short-link/{shortCode}/stats")
    public Result<ShortLinkStatsVO> getStats(
            @PathVariable String shortCode) {

        ShortLinkStatsVO stats =
                shortLinkService.getStats(shortCode);

        return Result.success(stats);
    }
    @GetMapping("/api/v1/short-link/{shortCode}/stats1")
    public Result<ShortLinkDetailVO> getStats1(
            @PathVariable String shortCode) {

        ShortLinkDetailVO vo =
                shortLinkService.getShortLinkDetail(shortCode);

        return Result.success(vo);
    }

    // 8.1 查询单个短链接
    @GetMapping("/api/v1/short-link/{shortCode}")
    public Result<ShortLinkManageVO> getShortLink(
            @PathVariable String shortCode) {

        ShortLinkManageVO vo =
                shortLinkService.getShortLinkByCode(shortCode);

        return Result.success(vo);
    }

    // 8.2 查询短链接列表（最简单列表）
    // 8.3 分页查询：追加 ?page=1&size=10 即返回分页结构
    // 同一个 list 接口，不传 size 走「全部列表」，传 size>0 走「分页」
    @GetMapping("/api/v1/short-link/list")
    public Result<?> listShortLinks(
            @RequestParam(required = false, defaultValue = "1") long page,
            @RequestParam(required = false, defaultValue = "0") long size) {

        // size > 0：分页查询
        if (size > 0) {
            PageResult<ShortLinkManageVO> pageResult =
                    shortLinkService.listShortLinksByPage(page, size);
            return Result.success(pageResult);
        }

        // 否则：返回全部列表（最简单的列表）
        return Result.success(shortLinkService.listShortLinks());
    }

    // 阶段 9：删除 / 失效短链接（物理清理 MySQL + 相关 Redis 键）
    @DeleteMapping("/api/v1/short-link/{shortCode}")
    public Result<String> deleteShortLink(
            @PathVariable String shortCode) {

        shortLinkService.deleteShortLink(shortCode);

        return Result.success("删除成功");
    }

    @GetMapping("/sync/{shortCode}")
    public Result<String> syncClickCount(
            @PathVariable String shortCode) {

        shortLinkService.syncClickCountToMySQL(shortCode);

        return Result.success("同步成功");
    }
}
