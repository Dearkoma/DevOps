package com.devops.platform.controller;

import com.devops.platform.entity.Template;
import com.devops.platform.repository.TemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateRepository templateRepository;

    @GetMapping
    public List<Template> getAll(@RequestParam(required = false) String type) {
        if (type != null) {
            return templateRepository.findByType(type);
        }
        return templateRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Template> getOne(@PathVariable Long id) {
        return templateRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/search")
    public List<Template> search(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String language) {
        if (type != null && category != null && language != null) {
            return templateRepository.findByTypeAndLanguageAndFramework(type, language, null);
        }
        if (type != null && category != null) {
            return templateRepository.findByCategoryAndType(category, type);
        }
        if (type != null) {
            return templateRepository.findByType(type);
        }
        return templateRepository.findAll();
    }

    @GetMapping("/builtin")
    public List<Template> getBuiltins() {
        return templateRepository.findByIsBuiltinTrueAndEnabledTrue();
    }

    @PostMapping
    public Template create(@RequestBody Template template) {
        return templateRepository.save(template);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Template> update(@PathVariable Long id, @RequestBody Template template) {
        return templateRepository.findById(id).map(existing -> {
            template.setId(id);
            return ResponseEntity.ok(templateRepository.save(template));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        templateRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }
}
