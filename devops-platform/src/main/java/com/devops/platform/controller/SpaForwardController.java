package com.devops.platform.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * SPA 前端路由回退 —— 非 API、非静态资源的请求转发到 index.html
 */
@Controller
public class SpaForwardController {

    /**
     * 匹配所有不包含 "." 的路径（即非静态资源路径），Spring MVC 优先匹配 /api/** 控制器，
     * 未匹配到的才会到这里，转发到 index.html 由 React Router 接管
     */
    @RequestMapping(value = "/{path:[^.]+}")
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}
