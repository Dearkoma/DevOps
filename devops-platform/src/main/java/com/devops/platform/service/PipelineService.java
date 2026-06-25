package com.devops.platform.service;
import com.devops.platform.entity.Pipeline;
import com.devops.platform.repository.PipelineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
@Service
@RequiredArgsConstructor
public class PipelineService {
    private final PipelineRepository pipelineRepository;
    public List<Pipeline> getPipelinesByProject(Long projectId) {
        return pipelineRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }
    public List<Pipeline> getAllPipelines() {
        return pipelineRepository.findAll();
    }
    public Pipeline getPipeline(Long id) { return pipelineRepository.findById(id).orElse(null); }
    public Pipeline createPipeline(Pipeline pipeline) {
        if (pipeline.getName() == null || pipeline.getName().isBlank()) {
            throw new IllegalArgumentException("流水线名称不能为空");
        }
        if (pipeline.getProjectId() == null) {
            throw new IllegalArgumentException("请选择所属项目");
        }
        // 确保 cronEnabled 有默认值
        if (pipeline.getCronEnabled() == null) {
            pipeline.setCronEnabled(false);
        }
        return pipelineRepository.save(pipeline);
    }

    public Pipeline updatePipeline(Long id, Pipeline pipeline) {
        Pipeline existing = getPipeline(id);
        if (existing == null) return null;
        existing.setName(pipeline.getName());
        existing.setDescription(pipeline.getDescription());
        existing.setDefinition(pipeline.getDefinition());
        existing.setStatus(pipeline.getStatus());
        existing.setBranchPattern(pipeline.getBranchPattern());
        existing.setCronExpression(pipeline.getCronExpression());
        if (pipeline.getCronEnabled() != null) {
            existing.setCronEnabled(pipeline.getCronEnabled());
        }
        return pipelineRepository.save(existing);
    }
    public void deletePipeline(Long id) { pipelineRepository.deleteById(id); }
    public List<Pipeline> findByProjectIdAndStatus(Long projectId, String status) {
        return pipelineRepository.findByProjectIdAndStatus(projectId, status);
    }
}
