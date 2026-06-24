package com.devops.platform.controller;
import com.devops.platform.entity.Pipeline;
import com.devops.platform.service.PipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
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
    public Pipeline create(@RequestBody Pipeline pipeline) { return pipelineService.createPipeline(pipeline); }
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
