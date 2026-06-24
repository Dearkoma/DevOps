package com.devops.platform.service;

import com.devops.platform.entity.User;
import com.devops.platform.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public Optional<User> getUserById(Long id) {
        return userRepository.findById(id);
    }

    public Optional<User> getUserByUsername(String username) {
        return userRepository.findByUsernameIgnoreCase(username);
    }

    public User createUser(User user) {
        if (userRepository.existsByUsernameIgnoreCase(user.getUsername())) {
            throw new IllegalArgumentException("用户名已存在: " + user.getUsername());
        }
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        if (user.getRole() == null || user.getRole().isBlank()) {
            user.setRole("DEVELOPER");
        }
        if (user.getEnabled() == null) {
            user.setEnabled(true);
        }
        return userRepository.save(user);
    }

    public Optional<User> updateUser(Long id, User updated) {
        return userRepository.findById(id).map(existing -> {
            if (updated.getUsername() != null && !updated.getUsername().isBlank()) {
                if (!existing.getUsername().equalsIgnoreCase(updated.getUsername())
                        && userRepository.existsByUsernameIgnoreCase(updated.getUsername())) {
                    throw new IllegalArgumentException("用户名已存在: " + updated.getUsername());
                }
                existing.setUsername(updated.getUsername());
            }
            if (updated.getPassword() != null && !updated.getPassword().isBlank()) {
                existing.setPassword(passwordEncoder.encode(updated.getPassword()));
            }
            if (updated.getEmail() != null) {
                existing.setEmail(updated.getEmail());
            }
            if (updated.getRealName() != null) {
                existing.setRealName(updated.getRealName());
            }
            if (updated.getRole() != null && !updated.getRole().isBlank()) {
                existing.setRole(updated.getRole());
            }
            if (updated.getEnabled() != null) {
                existing.setEnabled(updated.getEnabled());
            }
            return userRepository.save(existing);
        });
    }

    public boolean deleteUser(Long id) {
        return userRepository.findById(id).map(user -> {
            if ("admin".equalsIgnoreCase(user.getUsername())) {
                throw new IllegalArgumentException("不能删除超级管理员账号");
            }
            userRepository.delete(user);
            return true;
        }).orElse(false);
    }

    public long getUserCount() {
        return userRepository.count();
    }
}
