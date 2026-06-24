package com.devops.platform.aspect;

import com.devops.platform.entity.AuditLog;
import com.devops.platform.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.annotation.*;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.Map;

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 审计注解 */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Audit {
        String action();    // CREATE / UPDATE / DELETE / TRIGGER / CANCEL / APPROVE / REJECT / LOGIN
        String resource();  // PROJECT / PIPELINE / BUILD / ENVIRONMENT / USER / DEPLOYMENT
    }

    @Around("@annotation(audit)")
    public Object audit(ProceedingJoinPoint joinPoint, Audit audit) throws Throwable {
        AuditLog auditLog = new AuditLog();
        auditLog.setAction(audit.action());
        auditLog.setResource(audit.resource());
        auditLog.setCreatedAt(LocalDateTime.now());

        // 获取用户名
        String username = getUsername(joinPoint);
        auditLog.setUsername(username != null ? username : "anonymous");

        // 获取 IP
        auditLog.setIpAddress(getClientIp());

        // 获取参数中的资源 ID 和名称
        extractResourceInfo(joinPoint, auditLog);

        try {
            Object result = joinPoint.proceed();
            auditLog.setSuccess(true);
            auditLogRepository.save(auditLog);
            return result;
        } catch (Exception e) {
            auditLog.setSuccess(false);
            auditLog.setErrorMessage(e.getMessage());
            auditLogRepository.save(auditLog);
            throw e;
        }
    }

    private String getUsername(ProceedingJoinPoint joinPoint) {
        for (Object arg : joinPoint.getArgs()) {
            if (arg instanceof Principal) {
                return ((Principal) arg).getName();
            }
        }
        // 尝试从 SecurityContext 获取
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()) {
                return auth.getName();
            }
        } catch (Exception ignored) {}
        return "system";
    }

    private String getClientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes)
                    RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                String xf = request.getHeader("X-Forwarded-For");
                if (xf != null && !xf.isBlank()) return xf.split(",")[0].trim();
                return request.getRemoteAddr();
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    @SuppressWarnings("unchecked")
    private void extractResourceInfo(ProceedingJoinPoint joinPoint, AuditLog auditLog) {
        MethodSignature sig = (MethodSignature) joinPoint.getSignature();
        String[] paramNames = sig.getParameterNames();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < paramNames.length; i++) {
            String name = paramNames[i];
            Object val = args[i];
            if (val == null) continue;

            // 资源 ID
            if (("id".equals(name) || name.endsWith("Id")) && val instanceof Long) {
                auditLog.setResourceId((Long) val);
            }
            // 详细参数
            if (name.equals("data") || name.equals("project") || name.equals("pipeline")
                    || name.equals("env") || name.equals("environment") || name.equals("body")) {
                try {
                    Map<String, Object> map = objectMapper.convertValue(val, Map.class);
                    if (map.containsKey("name")) {
                        auditLog.setResourceName((String) map.get("name"));
                    }
                    auditLog.setDetail(objectMapper.writeValueAsString(map));
                } catch (Exception ignored) {}
            }
        }
    }
}
