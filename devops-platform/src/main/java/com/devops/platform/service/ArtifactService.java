package com.devops.platform.service;

import com.devops.platform.entity.Artifact;
import com.devops.platform.repository.ArtifactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArtifactService {

    private final ArtifactRepository artifactRepository;

    public List<Artifact> getArtifactsByBuild(Long buildId) {
        return artifactRepository.findByBuildIdOrderByCreatedAtDesc(buildId);
    }

    public List<Artifact> getArtifactsByType(String fileType) {
        return artifactRepository.findByFileType(fileType);
    }

    public Artifact saveArtifact(Artifact artifact) {
        return artifactRepository.save(artifact);
    }

    public Artifact getArtifact(Long id) {
        return artifactRepository.findById(id).orElse(null);
    }

    public void deleteArtifact(Long id) {
        artifactRepository.deleteById(id);
    }
}
