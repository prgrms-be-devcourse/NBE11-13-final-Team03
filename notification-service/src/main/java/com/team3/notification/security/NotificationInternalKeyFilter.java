package com.team3.notification.security;

import com.team3.notification.config.NotificationReceiverProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.function.Predicate;

/** Created only inside the Security chain, not registered again as a servlet filter. */
public class NotificationInternalKeyFilter extends OncePerRequestFilter {
    public static final String PATH = "/internal/notification-messages";
    public static final String AUTHORITY = "NOTIFICATION_PUBLISH";
    private final byte[] expected;
    private final Predicate<HttpServletRequest> matches;
    private final String authority;

    public NotificationInternalKeyFilter(NotificationReceiverProperties properties) {
        this(properties.apiKey(), request -> "POST".equals(request.getMethod())
                && (request.getContextPath() + PATH).equals(request.getRequestURI()), AUTHORITY);
    }

    public NotificationInternalKeyFilter(String key, Predicate<HttpServletRequest> matches, String authority) {
        expected = digest(key);
        this.matches = matches;
        this.authority = authority;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !matches.test(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var values = request.getHeaders("X-INTERNAL-KEY");
        String key = values.hasMoreElements() ? values.nextElement() : null;
        if (key == null || values.hasMoreElements() || !MessageDigest.isEqual(expected, digest(key))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken("application-service", null,
                List.of(new SimpleGrantedAuthority(authority))));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
