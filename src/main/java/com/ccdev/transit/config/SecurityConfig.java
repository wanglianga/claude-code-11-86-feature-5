package com.ccdev.transit.config;

import com.ccdev.transit.security.DbUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, DbUserDetailsService users) throws Exception {
        http
                // 隔离开发/演示服务：API 用 Basic，界面用表单登录；关闭 CSRF 以便登录表单与 POST 控制台操作
                .csrf(csrf -> csrf.disable())
                .userDetailsService(users)
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers("/", "/index.html", "/login.html", "/app.js",
                                "/styles.css", "/favicon.ico", "/assets/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html",
                                "/v3/api-docs/**", "/webjars/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/experiments/*/expansion/evaluate",
                                "/api/experiments/*/expansion/decide").hasRole("APPROVER")
                        .requestMatchers(HttpMethod.POST, "/api/**").hasAnyRole("ANALYST", "APPROVER")
                        .requestMatchers(HttpMethod.GET, "/api/**").authenticated()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login.html")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/index.html", true)
                        .failureUrl("/login.html?error=1")
                        .permitAll())
                .httpBasic(basic -> basic.realmName("transit-pilot-api"))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login.html?logout=1")
                        .permitAll());
        return http.build();
    }
}
