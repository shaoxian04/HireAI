package com.hireai.controller.biz.auth;

import com.hireai.application.biz.auth.AuthAppService;
import com.hireai.application.biz.auth.AuthResult;
import com.hireai.application.biz.auth.LoginInfo;
import com.hireai.application.biz.auth.RegisterInfo;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.auth.dto.BecomeBuilderRequest;
import com.hireai.controller.biz.auth.dto.LoginRequest;
import com.hireai.controller.biz.auth.dto.LoginResponse;
import com.hireai.controller.biz.auth.dto.RegisterRequest;
import com.hireai.controller.config.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication HTTP surface. Thin: validate the request, delegate to the app service, wrap the
 * result. {@code POST /api/auth/login} and {@code POST /api/auth/register} are permitAll in the
 * security chain (you cannot have a token before you authenticate). Login bad-credentials surface as
 * HTTP 401 via the global exception handler (generic message — no user enumeration); a duplicate
 * registration surfaces as HTTP 409. {@code POST /api/auth/become-builder} is JWT-gated; identity
 * is read from the JWT principal, never from the request body (Hard Invariant #5).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController extends BaseController {

    private final AuthAppService authAppService;
    private final CurrentUserProvider currentUserProvider;

    public AuthController(AuthAppService authAppService, CurrentUserProvider currentUserProvider) {
        this.authAppService = authAppService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/login")
    public WebResult<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResult result = authAppService.login(new LoginInfo(request.email(), request.password()));
        return ok(new LoginResponse(result.token(), result.userId(), result.roles()));
    }

    @PostMapping("/register")
    public WebResult<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResult result = authAppService.register(
                new RegisterInfo(request.email(), request.password(), request.displayName()));
        return ok(new LoginResponse(result.token(), result.userId(), result.roles()));
    }

    @PostMapping("/become-builder")
    public WebResult<LoginResponse> becomeBuilder(@Valid @RequestBody BecomeBuilderRequest request) {
        AuthResult result = authAppService.becomeBuilder(currentUserProvider.currentUserId());
        return ok(new LoginResponse(result.token(), result.userId(), result.roles()));
    }
}
