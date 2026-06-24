package com.devops.platform.controller;

import com.devops.platform.entity.ServiceInstance;
import com.devops.platform.repository.ServiceInstanceRepository;
import com.devops.platform.service.InstanceMonitorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/instances")
public class InstanceController {

    private final InstanceMonitorService monitorService;
    private final ServiceInstanceRepository instanceRepository;

    public InstanceController(InstanceMonitorService monitorService,
                              ServiceInstanceRepository instanceRepository) {
        this.monitorService = monitorService;
        this.instanceRepository = instanceRepository;
    }

    @GetMapping
    public List<ServiceInstance> getAll() {
        return monitorService.getAllInstances();
    }

    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        return monitorService.getStats();
    }

    @GetMapping("/project/{projectId}")
    public List<ServiceInstance> getByProject(@PathVariable Long projectId) {
        return monitorService.getInstancesByProject(projectId);
    }

    @PostMapping
    public ServiceInstance register(@RequestBody ServiceInstance instance) {
        return monitorService.saveInstance(instance);
    }

    @PutMapping("/{id}/health")
    public ResponseEntity<ServiceInstance> updateHealth(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        String healthStatus = (String) body.getOrDefault("healthStatus", "HEALTHY");
        Double cpu = body.containsKey("cpuUsage") ? ((Number) body.get("cpuUsage")).doubleValue() : null;
        Double mem = body.containsKey("memoryUsage") ? ((Number) body.get("memoryUsage")).doubleValue() : null;
        ServiceInstance inst = monitorService.updateHealth(id, healthStatus, cpu, mem);
        return inst == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(inst);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        instanceRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }
}
