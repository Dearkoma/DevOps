package com.devops.platform.controller;

import com.devops.platform.entity.User;
import com.devops.platform.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** 获取所有用户（ADMIN 专有，由 SecurityConfig 控制） */
    @GetMapping
    public List<User> getAll() {
        return userService.getAllUsers();
    }

    /** 获取单个用户 */
    @GetMapping("/{id}")
    public ResponseEntity<User> getOne(@PathVariable Long id) {
        return userService.getUserById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** 创建用户 */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody User user) {
        try {
            return ResponseEntity.ok(userService.createUser(user));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 更新用户 */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody User user) {
        try {
            return userService.updateUser(id, user)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 删除用户 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            boolean deleted = userService.deleteUser(id);
            return deleted ? ResponseEntity.ok(Map.of("success", true))
                    : ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
