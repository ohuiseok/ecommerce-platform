package com.ecommerce.monolith.common.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class JwtSecretStartupValidator {

    public static final String DEFAULT_JWT_SECRET = "ecommerce-monolith-secret-key-for-jwt-token-generation";

    private final Environment environment;
    private final String jwtSecret;

    public JwtSecretStartupValidator(Environment environment, @Value("${jwt.secret:}") String jwtSecret) {
        this.environment = environment;
        this.jwtSecret = jwtSecret;
    }

    @PostConstruct
    void validate() {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }

        if (!StringUtils.hasText(jwtSecret) || DEFAULT_JWT_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException("Production profile requires a non-default JWT secret.");
        }
    }
}
