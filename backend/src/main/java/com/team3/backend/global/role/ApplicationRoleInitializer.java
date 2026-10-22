package com.team3.backend.global.role;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Reject a deployment role that does not match the selected executable before bean registration. */
public final class ApplicationRoleInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private final String expectedRole;

    public ApplicationRoleInitializer(String expectedRole) {
        this.expectedRole = expectedRole;
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        String actualRole = context.getEnvironment().getProperty("app.role");
        if (!expectedRole.equals(actualRole)) {
            throw new IllegalStateException("Application entry point requires app.role="
                    + expectedRole + ", but configured app.role=" + actualRole);
        }
    }
}
