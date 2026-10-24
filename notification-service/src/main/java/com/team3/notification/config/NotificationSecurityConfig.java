package com.team3.notification.config;

import com.team3.notification.security.NotificationInternalKeyFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({NotificationReceiverProperties.class, NotificationUserApiProperties.class})
@PropertySource("classpath:notification-http.properties")
public class NotificationSecurityConfig {

    @Bean
    public SecurityFilterChain notificationSecurityFilterChain(HttpSecurity http, NotificationReceiverProperties properties,
                                                              NotificationUserApiProperties userApi) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(authorize -> {
                    if (userApi.enabled()) {
                        authorize.requestMatchers("/internal/users/*/notifications", "/internal/users/*/notifications/**",
                                        "/internal/users/*/notification-settings", "/internal/users/*/notification-endpoints",
                                        "/internal/users/*/notification-endpoints/*")
                                .hasAuthority("NOTIFICATION_USER_API");
                    }
                    if (properties.enabled()) {
                        authorize.requestMatchers(HttpMethod.POST, NotificationInternalKeyFilter.PATH)
                                .hasAuthority(NotificationInternalKeyFilter.AUTHORITY);
                    }
                    authorize.anyRequest().denyAll();
                });

        if (properties.enabled()) {
            http.addFilterBefore(new NotificationInternalKeyFilter(properties), AnonymousAuthenticationFilter.class);
        }
        if (userApi.enabled()) {
            // Different filter name: OncePerRequestFilter's marker must not skip the second filter.
            http.addFilterBefore(new NotificationInternalKeyFilter(userApi.apiKey(),
                    request -> request.getRequestURI().startsWith(request.getContextPath() + "/internal/users/"),
                    "NOTIFICATION_USER_API") {
                @Override protected String getAlreadyFilteredAttributeName() {
                    return "notificationUserApiKey.FILTERED";
                }
            }, AnonymousAuthenticationFilter.class);
        }

        return http.build();
    }
}
