package com.keralarescue.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Adds browser security headers to every response and rate-limits /api calls per client address. */
@Component
public class SecurityFilter extends OncePerRequestFilter {
    private final RateLimiter login = new RateLimiter(20, 60_000);      // sign-in attempts
    private final RateLimiter ai = new RateLimiter(15, 60_000);          // AI calls per address
    private final RateLimiter aiAll = new RateLimiter(150, 60_000);      // AI calls for the whole server (caps the API bill)
    private final RateLimiter sos = new RateLimiter(60, 60_000);         // new requests (phones share carrier addresses)
    private final RateLimiter streams = new RateLimiter(30, 60_000);     // live-update connections
    private final RateLimiter general = new RateLimiter(900, 60_000);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "geolocation=(self), camera=(), microphone=()");
        String path = request.getRequestURI();
        if (path.startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
            String ip = request.getRemoteAddr();
            boolean ok;
            RateLimiter used;
            if (path.startsWith("/api/auth/")) { used = login; ok = login.tryAcquire(ip); }
            else if (path.startsWith("/api/ai/") || path.equals("/api/translate")) { used = ai; ok = ai.tryAcquire(ip) && aiAll.tryAcquire("all"); }
            else if (path.equals("/api/stream") || path.equals("/api/helper/stream")) { used = streams; ok = streams.tryAcquire(ip); }
            else if (path.equals("/api/emergencies") && "POST".equalsIgnoreCase(request.getMethod())) { used = sos; ok = sos.tryAcquire(ip); }
            else { used = general; ok = general.tryAcquire(ip); }
            if (!ok) {
                response.setStatus(429);
                response.setHeader("Retry-After", String.valueOf(used.retryAfterSeconds()));
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Too many requests. Please wait a moment and try again.\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
