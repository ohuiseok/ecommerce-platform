package com.ecommerce.monolith.common;

import com.ecommerce.monolith.payment.client.MockPgClient;
import com.ecommerce.monolith.payment.client.UnavailablePgClient;
import com.ecommerce.monolith.payment.controller.MockPgWebhookController;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationProfileConfigTest {

    @Test
    void defaultProfileUsesLocalForDevelopmentOnlyComponents() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> propertySources = loader.load("application", new ClassPathResource("application.yml"));

        PropertySource<?> defaultPropertySource = propertySources.stream()
                .filter(propertySource -> propertySource.getProperty("spring.config.activate.on-profile") == null)
                .findFirst()
                .orElseThrow();

        assertThat(defaultPropertySource.getProperty("spring.profiles.default")).isEqualTo("local");
    }

    @Test
    void mockPgComponentsAreLimitedToLocalAndTestProfiles() {
        assertThat(profileValues(MockPgClient.class)).containsExactly("local", "test");
        assertThat(profileValues(MockPgWebhookController.class)).containsExactly("local", "test");
        assertThat(profileValues(UnavailablePgClient.class)).containsExactly("!local & !test");
    }

    @Test
    void prodProfileUsesHibernateValidate() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> propertySources = loader.load("application", new ClassPathResource("application.yml"));

        PropertySource<?> prodPropertySource = propertySources.stream()
                .filter(propertySource -> "prod".equals(propertySource.getProperty("spring.config.activate.on-profile")))
                .findFirst()
                .orElseThrow();

        assertThat(prodPropertySource.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(prodPropertySource.getProperty("spring.jpa.show-sql")).isEqualTo(false);
    }

    @Test
    void prodProfileLimitsActuatorExposureAndHealthDetails() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> propertySources = loader.load("application", new ClassPathResource("application.yml"));

        PropertySource<?> prodPropertySource = propertySources.stream()
                .filter(propertySource -> "prod".equals(propertySource.getProperty("spring.config.activate.on-profile")))
                .findFirst()
                .orElseThrow();

        assertThat(prodPropertySource.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
        assertThat(prodPropertySource.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
    }

    private String[] profileValues(Class<?> type) {
        MergedAnnotation<Profile> profile = MergedAnnotations.from(type).get(Profile.class);
        return profile.synthesize().value();
    }
}
