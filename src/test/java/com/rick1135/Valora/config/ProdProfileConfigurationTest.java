package com.rick1135.Valora.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ProdProfileConfigurationTest {
    @Test
    void loadsProductionSettings() throws IOException {
        PropertySource<?> properties = new YamlPropertySourceLoader()
                .load("prod", new ClassPathResource("application-prod.yml"))
                .getFirst();

        assertThat(properties.getProperty("springdoc.api-docs.enabled")).isEqualTo(false);
        assertThat(properties.getProperty("springdoc.swagger-ui.enabled")).isEqualTo(false);
        assertThat(properties.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,info,prometheus");
        assertThat(properties.getProperty("management.endpoint.health.probes.enabled")).isEqualTo(true);
        assertThat(properties.getProperty("management.endpoint.health.show-details")).isEqualTo("when_authorized");
        assertThat(properties.getProperty("management.endpoint.health.roles")).isEqualTo("ADMIN");
        assertThat(properties.getProperty("server.forward-headers-strategy")).isEqualTo("framework");
        assertThat(properties.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(properties.getProperty("security.headers.hsts-enabled"))
                .isInstanceOfSatisfying(String.class, value -> assertThat(value).contains("true"));
    }
}
