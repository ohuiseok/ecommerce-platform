package com.ecommerce.monolith.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtSecretStartupValidatorTest {

    @Test
    void prodProfileRejectsDefaultJwtSecret() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        JwtSecretStartupValidator validator = new JwtSecretStartupValidator(
                environment,
                JwtSecretStartupValidator.DEFAULT_JWT_SECRET
        );

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Production profile requires a non-default JWT secret.");
    }

    @Test
    void prodProfileRejectsBlankJwtSecret() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        JwtSecretStartupValidator validator = new JwtSecretStartupValidator(environment, " ");

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Production profile requires a non-default JWT secret.");
    }

    @Test
    void prodProfileAcceptsCustomJwtSecret() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        JwtSecretStartupValidator validator = new JwtSecretStartupValidator(
                environment,
                "production-secret-key-with-at-least-32-characters"
        );

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void localProfileAllowsDefaultJwtSecret() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        JwtSecretStartupValidator validator = new JwtSecretStartupValidator(
                environment,
                JwtSecretStartupValidator.DEFAULT_JWT_SECRET
        );

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }
}
