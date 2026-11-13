package com.edumy.identity.security;

import java.time.Clock;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.edumy.shared.config.EdumyProperties;
import com.edumy.shared.error.ErrorCode;
import com.edumy.shared.error.ProblemResponder;
import com.edumy.shared.security.AuthenticatedUser;
import com.edumy.shared.security.CurrentUser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Spring Security setup (docs/ai/04, ADR-002): stateless bearer JWT (HS256) resource server, CSRF off, CORS from the
 * MVC mapping in {@code shared.config.WebConfig}, method security for the access beans.
 * <p>Route rules (§4.3, Q2): public {@code /api/v1/auth/**} except {@code POST /auth/logout} and
 * {@code POST /auth/email-verification/resend}; public catalog, webhooks (HMAC-checked in P07), actuator
 * health/info, API docs and {@code /error}; {@code /api/v1/admin/**} needs ADMIN; everything else needs login.
 * <p>401/403 raised by the filter chain are rendered by {@code GlobalExceptionHandler} through
 * {@link ProblemResponder} ({@code UNAUTHENTICATED} / {@code ACCESS_DENIED}), so they have the same ProblemDetail shape
 * and {@code traceId} as every other error.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    static final int MIN_SECRET_BYTES = 32;
    private static final String ROLE_PREFIX = "ROLE_";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemResponder problems) throws Exception {
        AuthenticationEntryPoint unauthenticated = (request, response, ex) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            problems.respond(request, response, ErrorCode.UNAUTHENTICATED);
        };
        AccessDeniedHandler denied = (request, response, ex) ->
                problems.respond(request, response, ErrorCode.ACCESS_DENIED);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // Q2: these two auth endpoints act on the current session / account
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/logout", "/api/v1/auth/email-verification/resend").authenticated()
                        .requestMatchers("/api/v1/auth/**", "/api/v1/catalog/**", "/api/v1/webhooks/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                        .permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(denied))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(denied));
        return http.build();
    }

    @Bean
    JwtEncoder jwtEncoder(EdumyProperties properties) {
        return NimbusJwtEncoder.withSecretKey(secretKey(properties.security().jwt().secret()))
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * HS256 signature, then {@code exp}/{@code nbf} against the injected {@link Clock} and {@code iss = edumy}; only a
     * token passing those reaches {@link JwtSessionValidator} (no DB/cache lookup for expired or foreign tokens).
     */
    @Bean
    JwtDecoder jwtDecoder(EdumyProperties properties, Clock clock, JwtSessionValidator sessionValidator) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties.security().jwt().secret()))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> standard =
                new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator(JwtIssuer.ISSUER));
        decoder.setJwtValidator(jwt -> {
            OAuth2TokenValidatorResult result = standard.validate(jwt);
            return result.hasErrors() ? result : sessionValidator.validate(jwt);
        });
        return decoder;
    }

    /** JWT {@code roles} (lower-case codes) → {@code ROLE_STUDENT}, {@code ROLE_INSTRUCTOR}, {@code ROLE_ADMIN}. */
    static JwtAuthenticationConverter jwtAuthenticationConverter() {
        Converter<Jwt, Collection<GrantedAuthority>> authorities = jwt -> AuthenticatedUser.from(jwt)
                .map(CurrentUser::roles)
                .map(roles -> roles.stream()
                        .<GrantedAuthority>map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role.name()))
                        .toList())
                .orElse(List.of());
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * {@code edumy.security.jwt.secret}: base64 of at least {@value #MIN_SECRET_BYTES} random bytes (env
     * {@code EDUMY_JWT_SECRET}). Fails start-up otherwise; the message never contains the value (NFR-08).
     */
    static SecretKey secretKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(
                    "edumy.security.jwt.secret is not set (env EDUMY_JWT_SECRET, base64 of >= 32 random bytes)");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("edumy.security.jwt.secret is not valid base64");
        }
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("edumy.security.jwt.secret must decode to at least " + MIN_SECRET_BYTES
                    + " bytes (HS256), got " + bytes.length);
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
