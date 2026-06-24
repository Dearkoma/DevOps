package com.devops.platform.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * API 索引控制器
 * 提供所有可用的 API 端点列表
 */
@RestController
@RequestMapping("/api")
public class ApiIndexController {

    @GetMapping
    public Map<String, Object> index() {
        return Map.of(
            "name", "DevOps Continuous Delivery Platform API",
            "version", "1.0.0",
            "endpoints", List.of(
                Map.of("method", "GET",    "path", "/api/dashboard/stats",       "desc", "获取看板统计数据"),
                Map.of("method", "GET",    "path", "/api/dashboard/recent-builds", "desc", "获取最近构建记录"),
                Map.of("method", "GET",    "path", "/api/projects",               "desc", "获取所有项目"),
                Map.of("method", "GET",    "path", "/api/projects/{id}",          "desc", "获取项目详情"),
                Map.of("method", "POST",   "path", "/api/projects",               "desc", "创建项目"),
                Map.of("method", "PUT",    "path", "/api/projects/{id}",          "desc", "更新项目"),
                Map.of("method", "DELETE", "path", "/api/projects/{id}",          "desc", "删除项目"),
                Map.of("method", "GET",    "path", "/api/pipelines",              "desc", "获取所有流水线"),
                Map.of("method", "GET",    "path", "/api/pipelines/{id}",         "desc", "获取流水线详情"),
                Map.of("method", "POST",   "path", "/api/pipelines",              "desc", "创建流水线"),
                Map.of("method", "PUT",    "path", "/api/pipelines/{id}",         "desc", "更新流水线"),
                Map.of("method", "DELETE", "path", "/api/pipelines/{id}",         "desc", "删除流水线"),
                Map.of("method", "GET",    "path", "/api/builds",                 "desc", "获取构建记录"),
                Map.of("method", "GET",    "path", "/api/builds/{id}",            "desc", "获取构建详情"),
                Map.of("method", "POST",   "path", "/api/builds/trigger",         "desc", "触发构建"),
                Map.of("method", "GET",    "path", "/api/builds/{id}/log",        "desc", "获取构建日志"),
                Map.of("method", "DELETE", "path", "/api/builds/{id}/cancel",     "desc", "取消构建"),
                Map.of("method", "GET",    "path", "/api/environments",           "desc", "获取所有环境"),
                Map.of("method", "GET",    "path", "/api/environments/{id}",      "desc", "获取环境详情"),
                Map.of("method", "POST",   "path", "/api/environments",           "desc", "创建环境"),
                Map.of("method", "PUT",    "path", "/api/environments/{id}",      "desc", "更新环境"),
                Map.of("method", "DELETE", "path", "/api/environments/{id}",      "desc", "删除环境")
            )
        );
    }
}
