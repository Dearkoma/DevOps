package com.devops.platform.controller;

import com.devops.platform.config.JwtUtil;
import com.devops.platform.dto.AuthResponse;
import com.devops.platform.dto.LoginRequest;
import com.devops.platform.dto.RegisterRequest;
import com.devops.platform.entity.User;
import com.devops.platform.repository.UserRepository;
import com.devops.platform.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final UserService userService;

    public AuthController(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          JwtUtil jwtUtil,
                          UserService userService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.userService = userService;
    }

    /**
     * 用户登录
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        // 大小写不敏感查找
        User user = userRepository.findByUsernameIgnoreCase(request.getUsername())
                .orElseThrow(() -> new BadCredentialsException("用户名或密码错误"));

        if (!user.getEnabled()) {
            return ResponseEntity.status(403).body(Map.of("error", "账号已被禁用，请联系管理员"));
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BadCredentialsException("用户名或密码错误");
        }

        String token = jwtUtil.generateToken(user.getUsername(), user.getRole());

        return ResponseEntity.ok(AuthResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .userId(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .email(user.getEmail())
                .role(user.getRole())
                .build());
    }

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        if (userRepository.existsByUsernameIgnoreCase(request.getUsername())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "用户名已存在"));
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(request.getPassword()); // UserService will encode
        user.setEmail(request.getEmail());
        user.setRealName(request.getRealName());
        user.setRole("DEVELOPER"); // 新注册默认开发者角色

        User saved = userService.createUser(user);

        String token = jwtUtil.generateToken(saved.getUsername(), saved.getRole());

        return ResponseEntity.ok(AuthResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .userId(saved.getId())
                .username(saved.getUsername())
                .realName(saved.getRealName())
                .email(saved.getEmail())
                .role(saved.getRole())
                .build());
    }

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }

        User user = userRepository.findByUsernameIgnoreCase(auth.getName()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "用户不存在"));
        }

        return ResponseEntity.ok(AuthResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .email(user.getEmail())
                .role(user.getRole())
                .build());
    }
}
