package com.devops.platform.service;
import com.devops.platform.entity.Environment;
import com.devops.platform.repository.EnvironmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
@Service
@RequiredArgsConstructor
public class EnvironmentService {
    private final EnvironmentRepository environmentRepository;
    public List<Environment> getAllEnvironments() { return environmentRepository.findAll(); }
    public Environment getEnvironment(Long id) { return environmentRepository.findById(id).orElse(null); }
    public Environment createEnvironment(Environment env) { return environmentRepository.save(env); }
    public Environment updateEnvironment(Long id, Environment env) {
        Environment existing = getEnvironment(id);
        if (existing == null) return null;
        existing.setName(env.getName());
        existing.setDisplayName(env.getDisplayName());
        existing.setDescription(env.getDescription());
        existing.setStatus(env.getStatus());
        existing.setDeployUrl(env.getDeployUrl());
        return environmentRepository.save(existing);
    }
    public void deleteEnvironment(Long id) { environmentRepository.deleteById(id); }
    public Environment findByName(String name) { return environmentRepository.findByName(name); }
}
