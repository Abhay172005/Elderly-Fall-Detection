package com.smartelderly.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartelderly.dto.ApiError;
import com.smartelderly.security.JwtTokenService;
import com.smartelderly.security.ServiceKeyFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Stateless security: users authenticate with a Bearer JWT, the ML service with X-Service-Key.
 * Public: login/register, health. Admin-only: deleting incidents, changing settings (enforced with @PreAuthorize).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public JwtDecoder jwtDecoder(JwtTokenService tokens) {
        return tokens.decoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, AppProperties props, ObjectMapper mapper) throws Exception {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("role");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(roles);

        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .headers(h -> h.contentTypeOptions(Customizer.withDefaults()).frameOptions(f -> f.deny()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/register").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/registration-status", "/api/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/detection/fall").hasRole("SERVICE")
                        .requestMatchers("/api/**").hasAnyRole("ADMIN", "CAREGIVER")
                        .anyRequest().denyAll())
                .addFilterBefore(new ServiceKeyFilter(props.ml().serviceKey()), BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint((req, res, ex) -> writeError(mapper, req, res, HttpStatus.UNAUTHORIZED,
                                "Your session is missing or has expired. Please sign in again."))
                        .accessDeniedHandler((req, res, ex) -> writeError(mapper, req, res, HttpStatus.FORBIDDEN,
                                "You do not have permission to perform this action.")))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeError(mapper, req, res, HttpStatus.UNAUTHORIZED,
                                "Authentication is required."))
                        .accessDeniedHandler((req, res, ex) -> writeError(mapper, req, res, HttpStatus.FORBIDDEN,
                                "You do not have permission to perform this action.")));
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.cors().allowedOrigins());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cfg.setExposedHeaders(List.of("Content-Disposition"));
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }

    private static void writeError(ObjectMapper mapper, HttpServletRequest req, HttpServletResponse res,
                                   HttpStatus status, String message) throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(),
                new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, req.getRequestURI(), null));
    }
}
