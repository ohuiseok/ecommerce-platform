package com.ecommerce.monolith.common;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationProfileConfigTest {

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
}
