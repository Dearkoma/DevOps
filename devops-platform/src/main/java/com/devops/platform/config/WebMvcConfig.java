package com.devops.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * SPA 回退：非 API / 非静态资源请求返回 index.html
 * 仅处理 /static/ 下的资源请求，非资源请求由 SpaForwardController 处理
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 静态资源优先从 classpath:/static/ 提供
        registry.addResourceHandler("/static/**")
                .addResourceLocations("classpath:/static/");
    }
}
