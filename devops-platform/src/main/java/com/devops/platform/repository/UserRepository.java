package com.devops.platform.repository;

import com.devops.platform.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    /** 大小写不敏感查找 */
    Optional<User> findByUsernameIgnoreCase(String username);

    Boolean existsByUsername(String username);

    /** 大小写不敏感存在检查 */
    Boolean existsByUsernameIgnoreCase(String username);
}
