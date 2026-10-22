package com.team3.backend.config;

import com.team3.backend.global.role.ApiRole;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@Configuration
@EnableMethodSecurity
@ApiRole
public class MethodSecurityConfig {
}
