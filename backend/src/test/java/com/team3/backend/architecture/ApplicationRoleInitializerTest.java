package com.team3.backend.architecture;

import com.team3.backend.global.role.ApplicationRoleInitializer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationRoleInitializerTest {

    @ParameterizedTest
    @CsvSource({"api,api", "notification,notification"})
    void acceptsMatchingRole(String expected, String configured) {
        try (var context = new GenericApplicationContext()) {
            context.setEnvironment(new MockEnvironment().withProperty("app.role", configured));
            assertThatCode(() -> new ApplicationRoleInitializer(expected).initialize(context))
                    .doesNotThrowAnyException();
        }
    }

    @ParameterizedTest
    @CsvSource({"api,notification", "notification,api", "api,invalid", "notification,invalid"})
    void rejectsMismatchedRoleBeforeRefresh(String expected, String configured) {
        try (var context = new GenericApplicationContext()) {
            context.setEnvironment(new MockEnvironment().withProperty("app.role", configured));
            assertThatThrownBy(() -> new ApplicationRoleInitializer(expected).initialize(context))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("requires app.role=" + expected)
                    .hasMessageContaining("configured app.role=" + configured);
        }
    }
}
