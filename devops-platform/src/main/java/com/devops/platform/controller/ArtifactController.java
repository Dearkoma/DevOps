package com.devops.platform.controller;

import com.devops.platform.entity.Artifact;
import com.devops.platform.service.ArtifactService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/artifacts")
@RequiredArgsConstructor
public class ArtifactController {

    private final ArtifactService artifactService;

    @GetMapping
    public List<Artifact> getByBuild(@RequestParam Long buildId) {
        return artifactService.getArtifactsByBuild(buildId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Artifact> getOne(@PathVariable Long id) {
        Artifact a = artifactService.getArtifact(id);
        return a == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(a);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        artifactService.deleteArtifact(id);
        return ResponseEntity.ok().build();
    }
}
