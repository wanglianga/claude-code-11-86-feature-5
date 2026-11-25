package com.ccdev.transit.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 从 app_user 表加载账号；角色映射为 ROLE_*。 */
@Service
public class DbUserDetailsService implements UserDetailsService {

    private final JdbcTemplate jdbc;

    public DbUserDetailsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT username, password_hash, display_name, role FROM app_user WHERE username = ?",
                username);
        if (rows.isEmpty()) {
            throw new UsernameNotFoundException("用户不存在: " + username);
        }
        Map<String, Object> row = rows.get(0);
        String role = String.valueOf(row.get("role"));
        return User.withUsername(String.valueOf(row.get("username")))
                .password(String.valueOf(row.get("password_hash")))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role))
                .build();
    }
}
