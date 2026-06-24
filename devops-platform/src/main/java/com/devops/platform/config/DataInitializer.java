package com.devops.platform.config;

import com.devops.platform.entity.User;
import com.devops.platform.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 数据初始化器
 * 首次启动时自动创建默认管理员账户，之后不再重复创建。
 * 其余数据（项目/流水线/环境）通过前端界面手动添加。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${devops.admin.default-username:admin}")
    private String defaultUsername;

    @Value("${devops.admin.default-password:admin123}")
    private String defaultPassword;

    @Value("${devops.admin.default-email:admin@devops.local}")
    private String defaultEmail;

    @Override
    public void run(String... args) {
        String encodedPassword = passwordEncoder.encode(defaultPassword);

        // 仅在账号不存在时创建默认账号，已存在的账号不覆盖任何字段
        createIfAbsent(defaultUsername, encodedPassword, defaultEmail, "系统管理员", "ADMIN");
        createIfAbsent("manager", encodedPassword, "manager@devops.local", "项目经理", "MANAGER");
        createIfAbsent("developer", encodedPassword, "developer@devops.local", "开发者", "DEVELOPER");
        createIfAbsent("viewer", encodedPassword, "viewer@devops.local", "观察者", "VIEWER");
    }

    private void createIfAbsent(String username, String encodedPassword, String email, String realName, String role) {
        if (userRepository.findByUsernameIgnoreCase(username).isPresent()) {
            log.debug("账号 {} 已存在，跳过初始化", username);
            return;
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(encodedPassword);
        user.setEmail(email);
        user.setRealName(realName);
        user.setRole(role);
        user.setEnabled(true);
        userRepository.save(user);
        log.info("{} 账号已创建。用户名: {}  密码: {}", role, username, defaultPassword);
    }
}
