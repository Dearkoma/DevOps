package com.devops.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;
import java.util.Map;

/**
 * Spring Security 配置 - JWT 无状态认证 + 四级角色 RBAC
 * <p>
 * 角色层级：
 *   ADMIN     — 全部操作，含用户管理和审计日志
 *   MANAGER   — 项目/流水线/环境/模板/部署审批/定时任务 管理
 *   DEVELOPER — 触发构建、申请部署、查看资源
 *   VIEWER    — 只读，禁止任何写操作
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, ObjectMapper objectMapper) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    res.setCharacterEncoding("UTF-8");
                    objectMapper.writeValue(res.getWriter(),
                        Map.of("error", "未登录或 Token 已过期，请重新登录"));
                })
                .accessDeniedHandler((req, res, e) -> {
                    res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    res.setCharacterEncoding("UTF-8");
                    objectMapper.writeValue(res.getWriter(),
                        Map.of("error", "权限不足，无法访问该资源"));
                })
            )
            .authorizeHttpRequests(auth -> auth
                // ========== 公开接口 ==========
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/webhook/**").permitAll()
                .requestMatchers("/ws/**").permitAll()

                // ========== ADMIN 专属 ==========
                .requestMatchers("/api/users/**").hasRole("ADMIN")
                .requestMatchers("/api/audit/**").hasRole("ADMIN")

                // ========== ADMIN + MANAGER: 资源管理写操作 ==========
                // 项目
                .requestMatchers(HttpMethod.POST, "/api/projects").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.PUT, "/api/projects/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.DELETE, "/api/projects/**").hasAnyRole("ADMIN", "MANAGER")
                // 流水线
                .requestMatchers(HttpMethod.POST, "/api/pipelines").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.PUT, "/api/pipelines/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.DELETE, "/api/pipelines/**").hasAnyRole("ADMIN", "MANAGER")
                // 环境
                .requestMatchers(HttpMethod.POST, "/api/environments").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.PUT, "/api/environments/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.DELETE, "/api/environments/**").hasAnyRole("ADMIN", "MANAGER")
                // 模板
                .requestMatchers(HttpMethod.POST, "/api/templates").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.PUT, "/api/templates/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.DELETE, "/api/templates/**").hasAnyRole("ADMIN", "MANAGER")

                // ========== 构建操作 ==========
                // 触发构建: ADMIN + MANAGER + DEVELOPER
                .requestMatchers(HttpMethod.POST, "/api/builds/trigger").hasAnyRole("ADMIN", "MANAGER", "DEVELOPER")
                // 取消构建: ADMIN + MANAGER
                .requestMatchers(HttpMethod.DELETE, "/api/builds/**").hasAnyRole("ADMIN", "MANAGER")

                // ========== 部署操作 ==========
                // 申请部署: ADMIN + MANAGER + DEVELOPER
                .requestMatchers(HttpMethod.POST, "/api/deployments/request").hasAnyRole("ADMIN", "MANAGER", "DEVELOPER")
                // 审批/拒绝: ADMIN + MANAGER
                .requestMatchers("/api/deployments/approve/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers("/api/deployments/reject/**").hasAnyRole("ADMIN", "MANAGER")
                // 回滚: ADMIN + MANAGER
                .requestMatchers(HttpMethod.POST, "/api/deployments/rollback/**").hasAnyRole("ADMIN", "MANAGER")

                // ========== 制品操作 ==========
                .requestMatchers(HttpMethod.DELETE, "/api/artifacts/**").hasAnyRole("ADMIN", "MANAGER")

                // ========== 定时任务 ==========
                .requestMatchers("/api/scheduler/**").hasAnyRole("ADMIN", "MANAGER")

                // ========== 其余 API: 只要登录即可 (VIEWER 可读) ==========
                .requestMatchers("/api/**").authenticated()

                // ========== 静态资源: 公开 ==========
                .anyRequest().permitAll()
            )
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.setAllowedOriginPatterns(List.of("*"));
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public CorsFilter corsFilter() {
        return new CorsFilter(corsConfigurationSource());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
