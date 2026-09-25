package com.linkforge.service;

/**
 * 跳转鉴权结果。
 *
 * 一个短链接在「公开 / 仅创建者」两种模式下，重定向前的访问决策：
 * - OK：允许跳转，携带目标长链接；
 * - UNAUTHORIZED：受限短链但未登录（或 token 失效/已登出），应返回 HTTP 401；
 * - FORBIDDEN：已登录但非创建者，应返回 HTTP 403；
 * - NOT_FOUND：短链不存在或已过期，应返回 HTTP 404。
 */
public class RedirectResult {

    public enum Status { OK, UNAUTHORIZED, FORBIDDEN, NOT_FOUND }

    private final Status status;
    private final String originUrl;

    private RedirectResult(Status status, String originUrl) {
        this.status = status;
        this.originUrl = originUrl;
    }

    public static RedirectResult ok(String originUrl) {
        return new RedirectResult(Status.OK, originUrl);
    }

    public static RedirectResult unauthorized() {
        return new RedirectResult(Status.UNAUTHORIZED, null);
    }

    public static RedirectResult forbidden() {
        return new RedirectResult(Status.FORBIDDEN, null);
    }

    public static RedirectResult notFound() {
        return new RedirectResult(Status.NOT_FOUND, null);
    }

    public Status getStatus() {
        return status;
    }

    public String getOriginUrl() {
        return originUrl;
    }
}
