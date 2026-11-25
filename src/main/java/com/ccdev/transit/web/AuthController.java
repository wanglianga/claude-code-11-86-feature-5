package com.ccdev.transit.web;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class AuthController {

    @GetMapping("/api/me")
    public Map<String, Object> me(Authentication authentication) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            m.put("authenticated", false);
            return m;
        }
        m.put("authenticated", true);
        m.put("username", authentication.getName());
        m.put("roles", authentication.getAuthorities().stream()
                .map(a -> a.getAuthority().replace("ROLE_", "")).toList());
        return m;
    }
}
