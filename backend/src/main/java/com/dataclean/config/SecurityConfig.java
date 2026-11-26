package com.dataclean.config;

import com.dataclean.filter.AnonymousSessionFilter;
import com.dataclean.filter.JwtAuthFilter;
import com.dataclean.filter.PublicOriginGuardFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置（双模式）。
 *
 * <p>public：免登录，仅匿名会话；认证/配置/模板/历史/管理接口一律拒绝。
 * private：沿用第二阶段 JWT 登录，仅额外放行 {@code /api/public/config} 只读配置。
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final AnonymousSessionFilter anonymousSessionFilter;
    private final PublicOriginGuardFilter publicOriginGuardFilter;
    private final AppMode appMode;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .cors(cors -> {});

        if (appMode.isPublic()) {
            http.authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/health").permitAll()
                    // 公开模式彻底关闭身份与配置能力
                    .requestMatchers("/api/auth/**").denyAll()
                    .requestMatchers("/api/ai/config", "/api/ai/config/**",
                            "/api/ai/template", "/api/ai/template/**",
                            "/api/ai/governance", "/api/ai/governance/**",
                            "/api/ai/history", "/api/ai/history/**").denyAll()
                    .requestMatchers("/api/admin/**", "/api/history/**", "/api/audit/**").denyAll()
                    .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico", "/*.js", "/*.css").permitAll()
                    .anyRequest().permitAll()
            )
            // 被拒绝的私有能力统一返回 404，不暴露接口是否存在
            // 注意：匿名用户触发 denyAll 时走 authenticationEntryPoint，必须同时覆盖
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint((request, response, e) -> writeNotFound(response))
                    .accessDeniedHandler((request, response, e) -> writeNotFound(response)))
            // 先校验写操作来源，再解析匿名会话
            .addFilterBefore(publicOriginGuardFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(anonymousSessionFilter, PublicOriginGuardFilter.class);
        } else {
            http.authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/health").permitAll()
                    .requestMatchers("/api/auth/**").permitAll()
                    .requestMatchers("/api/ai/generate/stream").permitAll()
                    // 公开只读配置：私有模式也可读取，便于前端识别模式
                    .requestMatchers("/api/public/config").permitAll()
                    .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico", "/*.js", "/*.css").permitAll()
                    .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }

    /** public 模式下所有被拒绝访问统一按 404 处理，避免泄露接口/资源是否存在。 */
    private static void writeNotFound(jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        response.setStatus(404);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":404,\"message\":\"资源不存在\"}");
    }
}
