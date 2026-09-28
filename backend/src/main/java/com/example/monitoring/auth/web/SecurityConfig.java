package com.example.monitoring.auth.web;

import com.example.monitoring.common.api.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final BearerAuthenticationFilter bearerAuthenticationFilter;
    private final ApiRateLimitFilter apiRateLimitFilter;
    private final ApiSecurityErrorWriter errorWriter;
    private final Environment environment;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(
                                "/api/v1/auth/csrf",
                                "/api/v1/auth/signup",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout",
                                "/actuator/health").permitAll();
                    String[] operations = {"/actuator/info", "/actuator/metrics", "/actuator/metrics/**",
                            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"};
                    if (environment.acceptsProfiles(Profiles.of("local"))) auth.requestMatchers(operations).permitAll();
                    else auth.requestMatchers(operations).hasRole("ADMIN");
                    auth.requestMatchers("/api/**").authenticated().anyRequest().permitAll();
                })
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> errorWriter.write(
                                request, response,
                                new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "인증이 필요합니다.")))
                        .accessDeniedHandler((request, response, exception) -> errorWriter.write(
                                request, response,
                                new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다."))))
                .addFilterBefore(bearerAuthenticationFilter, AnonymousAuthenticationFilter.class)
                .addFilterAfter(apiRateLimitFilter, BearerAuthenticationFilter.class)
                .build();
    }

    @Bean
    public FilterRegistrationBean<BearerAuthenticationFilter> disableContainerBearerFilterRegistration(
            BearerAuthenticationFilter filter) {
        FilterRegistrationBean<BearerAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ApiRateLimitFilter> disableContainerApiRateLimitFilterRegistration(
            ApiRateLimitFilter filter) {
        FilterRegistrationBean<ApiRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
