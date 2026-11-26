package com.dataclean.filter;

import com.dataclean.config.AppMode;
import com.dataclean.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * JWT 认证过滤器（私有模式）。
 *
 * <p>公开模式下完全跳过：公开入口只认匿名会话 Cookie，不接受任何 JWT。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final AppMode appMode;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (appMode.isPublic()) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader(jwtUtil.getHeader());
        String token = jwtUtil.extractToken(authHeader);

        if (token != null && jwtUtil.validateToken(token) && !jwtUtil.isTokenExpired(token)) {
            Long userId = jwtUtil.getUserId(token);
            String username = jwtUtil.getUsername(token);

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, null, Collections.emptyList());
            authentication.setDetails(username);

            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }
}
