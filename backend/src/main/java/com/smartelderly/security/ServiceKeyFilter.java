package com.smartelderly.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Authenticates machine-to-machine calls from the Python ML service using the shared {@code X-Service-Key} header.
 * A valid key grants only ROLE_SERVICE, which is accepted solely on the detection callback endpoint.
 */
public class ServiceKeyFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Service-Key";
    private final byte[] expected;

    public ServiceKeyFilter(String serviceKey) {
        this.expected = serviceKey == null || serviceKey.isBlank() ? null : serviceKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (expected != null && provided != null
                && MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8))) {
            var auth = new UsernamePasswordAuthenticationToken("ml-service", null,
                    List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }
}
