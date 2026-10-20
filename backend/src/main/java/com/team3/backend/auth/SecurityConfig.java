package com.team3.backend.auth;

import com.team3.backend.auth.filter.InternalApiKeyFilter;
import com.team3.backend.auth.filter.TokenAuthenticationFilter;
import com.team3.backend.auth.oauth2.CustomAuthorizationRequestResolver;
import com.team3.backend.auth.oauth2.OAuth2FailureHandler;
import com.team3.backend.auth.oauth2.OAuth2SuccessHandler;
import com.team3.backend.auth.security.CustomAuthenticationEntryPoint;
import com.team3.backend.auth.service.CustomOAuth2UserService;
import com.team3.backend.global.logging.TraceIdFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final OAuth2FailureHandler oAuth2FailureHandler;
    private final TokenAuthenticationFilter tokenAuthenticationFilter;
    private final InternalApiKeyFilter internalApiKeyFilter;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAuthorizationRequestResolver customAuthorizationRequestResolver;
    private final TraceIdFilter traceIdFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CustomOAuth2UserService customOAuth2UserService
    ) throws Exception {
        http
                .csrf(csrf -> {
                    csrf.spa();
                    csrf.ignoringRequestMatchers("/api/internal/**");
                })
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/",

                                // 정적 리소스
                                "/css/**",
                                "/js/**",
                                "/images/**",
                                "/swagger-ui",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/actuator/**",

                                // OAuth2
                                "/login-success.html",
                                "/login-failure.html",
                                "/oauth2/**",
                                "/login/**",
                                "/api/auth/reissue"

                        ).permitAll()

                        // 비회원도 조회 가능한 기회 API
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/opportunities",
                                "/api/opportunities/{opportunityId}"
                        )
                        .permitAll()

                        // 내부 자동화 API
                        .requestMatchers("/api/internal/**")
                        .permitAll()

                        // 그 외 API
                        .requestMatchers("/api/**")
                        .hasAnyAuthority("USER", "ADMIN")

                        .anyRequest().authenticated()
                )

                // 소셜 로그인
                .oauth2Login(oauth -> oauth
                        .authorizationEndpoint(endpoint -> endpoint
                                .authorizationRequestResolver(
                                        customAuthorizationRequestResolver
                                )
                        )
                        .userInfoEndpoint(
                                userInfo ->
                                        userInfo.userService(customOAuth2UserService)
                        )
                        .successHandler(oAuth2SuccessHandler)
                        .failureHandler(oAuth2FailureHandler)
                )

                .exceptionHandling(exception -> exception
                        .defaultAuthenticationEntryPointFor(
                                authenticationEntryPoint,
                                PathPatternRequestMatcher.withDefaults()
                                        .matcher("/api/**")
                        )
                )

                // 내부 API Key 검사
                .addFilterBefore(
                        internalApiKeyFilter,
                        UsernamePasswordAuthenticationFilter.class
                )

                // access token 검사
                .addFilterBefore(
                        tokenAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                )

                .addFilterBefore(
                        traceIdFilter,
                        TokenAuthenticationFilter.class
                );

        return http.build();
    }
}
