package com.devops.platform.controller;
import com.devops.platform.entity.Environment;
import com.devops.platform.service.EnvironmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/environments")
@RequiredArgsConstructor
public class EnvironmentController {
    private final EnvironmentService environmentService;
    @GetMapping
    public List<Environment> getAll() { return environmentService.getAllEnvironments(); }
    @GetMapping("/{id}")
    public ResponseEntity<Environment> getOne(@PathVariable Long id) {
        Environment e = environmentService.getEnvironment(id);
        return e == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(e);
    }
    @PostMapping
    public Environment create(@RequestBody Environment env) {
        return environmentService.createEnvironment(env);
    }
    @PutMapping("/{id}")
    public ResponseEntity<Environment> update(@PathVariable Long id, @RequestBody Environment env) {
        Environment e = environmentService.updateEnvironment(id, env);
        return e == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(e);
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        environmentService.deleteEnvironment(id);
        return ResponseEntity.ok().build();
    }
}
