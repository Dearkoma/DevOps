package com.devops.platform.controller;
import com.devops.platform.entity.Pipeline;
import com.devops.platform.service.PipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pipelines")
@RequiredArgsConstructor
public class PipelineController {
    private final PipelineService pipelineService;
    @GetMapping
    public List<Pipeline> getAll(@RequestParam(required = false) Long projectId) {
        if (projectId != null) return pipelineService.getPipelinesByProject(projectId);
        return pipelineService.getAllPipelines();
    }
    @GetMapping("/{id}")
    public ResponseEntity<Pipeline> getOne(@PathVariable Long id) {
        Pipeline p = pipelineService.getPipeline(id);
        return p == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(p);
    }
    @PostMapping
    public ResponseEntity<?> create(@RequestBody Pipeline pipeline) {
        try {
            Pipeline created = pipelineService.createPipeline(pipeline);
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
    @PutMapping("/{id}")
    public ResponseEntity<Pipeline> update(@PathVariable Long id, @RequestBody Pipeline pipeline) {
        Pipeline p = pipelineService.updatePipeline(id, pipeline);
        return p == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(p);
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        pipelineService.deletePipeline(id);
        return ResponseEntity.ok().build();
    }
}
