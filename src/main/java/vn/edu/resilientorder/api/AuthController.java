package vn.edu.resilientorder.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.resilientorder.security.AuthenticatedUser;
import vn.edu.resilientorder.security.AuthenticationResult;
import vn.edu.resilientorder.security.AuthenticationService;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationService authenticationService;
    private final SecurityContextRepository securityContextRepository;
    private final int sessionIdleTimeoutSeconds;

    public AuthController(
            AuthenticationService authenticationService,
            SecurityContextRepository securityContextRepository,
            @Value("${server.servlet.session.timeout:30m}") java.time.Duration sessionIdleTimeout
    ) {
        this.authenticationService = authenticationService;
        this.securityContextRepository = securityContextRepository;
        this.sessionIdleTimeoutSeconds = Math.toIntExact(sessionIdleTimeout.toSeconds());
    }

    @GetMapping("/csrf")
    Map<String, String> csrfToken(CsrfToken csrfToken) {
        return Map.of("token", csrfToken.getToken(), "headerName", csrfToken.getHeaderName());
    }

    @PostMapping("/login")
    ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            jakarta.servlet.http.HttpServletResponse servletResponse
    ) {
        AuthenticationResult result = authenticationService.authenticate(
                request.username(), request.password(), servletRequest.getRequestURI()
        );
        if (!result.isAuthenticated()) {
            HttpStatus status = result.failure() == AuthenticationResult.Failure.ACCOUNT_LOCKED
                    ? HttpStatus.LOCKED : HttpStatus.UNAUTHORIZED;
            String detail = result.failure() == AuthenticationResult.Failure.ACCOUNT_LOCKED
                    ? "Account is locked. Try again after the 15-minute lockout."
                    : "Invalid username or password.";
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
            problem.setTitle(result.failure() == AuthenticationResult.Failure.ACCOUNT_LOCKED
                    ? "ACCOUNT_LOCKED" : "AUTHENTICATION_FAILED");
            return ResponseEntity.status(status).body(problem);
        }

        AuthenticatedUser user = result.user();
        if (servletRequest.getSession(false) != null) {
            servletRequest.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                user, null, user.getAuthorities()
        ));
        SecurityContextHolder.setContext(context);

        HttpSession session = servletRequest.getSession(true);
        session.setMaxInactiveInterval(sessionIdleTimeoutSeconds);
        securityContextRepository.saveContext(context, servletRequest, servletResponse);
        return ResponseEntity.ok(LoginResponse.from(user, sessionIdleTimeoutSeconds));
    }

    @GetMapping("/me")
    LoginResponse currentUser(
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return LoginResponse.from(user, sessionIdleTimeoutSeconds);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }
}
