package com.hireai.controller.config;

import java.util.UUID;

/**
 * Resolves the identity of the caller. Per the hard invariant "server-side
 * identity from JWT", controllers obtain the current user ID ONLY through this
 * abstraction, never from a request path or body.
 *
 * The dev implementation returns a fixed user; the real implementation will read
 * the JWT principal. Swapping the bean is the only change required when auth lands.
 */
public interface CurrentUserProvider {

    UUID currentUserId();
}
