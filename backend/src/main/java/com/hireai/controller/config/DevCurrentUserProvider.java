package com.hireai.controller.config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Development stand-in for {@link CurrentUserProvider}. Returns a fixed seeded
 * user so the Wallet slice is exercisable before JWT auth exists.
 *
 * TODO(auth): replace with a JwtCurrentUserProvider that reads the authenticated
 * principal. This bean is active only under the "test" profile; production uses JwtCurrentUserProvider.
 */
@Component
@Profile("test")
public class DevCurrentUserProvider implements CurrentUserProvider {

    /** Matches the seeded dev user in db/migration. */
    public static final UUID DEV_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Override
    public UUID currentUserId() {
        return DEV_USER_ID;
    }
}
