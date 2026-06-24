package com.devops.platform.controller;

import com.devops.platform.entity.AuditLog;
import com.devops.platform.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditLogRepository auditLogRepository;

    @GetMapping
    public Page<AuditLog> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return auditLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
    }

    @GetMapping("/user/{username}")
    public List<AuditLog> getByUser(@PathVariable String username) {
        return auditLogRepository.findByUsernameOrderByCreatedAtDesc(username);
    }

    @GetMapping("/resource/{resource}/{resourceId}")
    public List<AuditLog> getByResource(
            @PathVariable String resource,
            @PathVariable Long resourceId) {
        return auditLogRepository.findByResourceAndResourceIdOrderByCreatedAtDesc(resource, resourceId);
    }

    @GetMapping("/action/{action}")
    public List<AuditLog> getByAction(@PathVariable String action) {
        return auditLogRepository.findByActionOrderByCreatedAtDesc(action);
    }
}
