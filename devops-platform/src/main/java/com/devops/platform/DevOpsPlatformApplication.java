package com.devops.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DevOps 持续交付平台启动类
 * 端口：8080
 * 数据库：MySQL (localhost:3306/devops_platform)
 * 前端：http://localhost:8080/
 * API：http://localhost:8080/api
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class DevOpsPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(DevOpsPlatformApplication.class, args);
        printBanner();
    }

    private static void printBanner() {
        System.out.println("""
        ╔══════════════════════════════════════════════════════╗
        ║                                                      ║
        ║   ██████╗ ███████╗██████╗ ██╗   ██╗███████╗        ║
        ║   ██╔══██╗██╔════╝██╔══██╗██║   ██║██╔════╝        ║
        ║   ██║  ██║█████╗  ██████╔╝██║   ██║███████╗        ║
        ║   ██║  ██║██╔══╝  ██╔══██╗██║   ██║╚════██║        ║
        ║   ██████╔╝███████╗██║  ██║╚█████╔╝███████║        ║
        ║   ╚═════╝ ╚══════╝╚═╝  ╚═╝ ╚════╝ ╚══════╝        ║
        ║                                                      ║
        ║         持续交付平台 v1.0.0                           ║
        ║         DevOps Continuous Delivery Platform           ║
        ║                                                      ║
        ║   🚀 前端：http://localhost:8080/                     ║
        ║   🗄️ 数据库：MySQL (localhost:3306/devops_platform)   ║
        ║   📚 API文档：http://localhost:8080/api               ║
        ║                                                      ║
        ╚══════════════════════════════════════════════════════╝
        """);
    }
}
