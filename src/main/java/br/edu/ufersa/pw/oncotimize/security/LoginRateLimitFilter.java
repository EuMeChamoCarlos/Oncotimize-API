package br.edu.ufersa.pw.oncotimize.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limita tentativas de login por IP (força bruta): LIMITE por janela de 1 minuto, depois 429 ProblemDetail.
 * Não é @Component de propósito: só entra na cadeia do Spring Security (SecurityFilter), não como filtro de servlet.
 */
@NullMarked
public class LoginRateLimitFilter extends OncePerRequestFilter {

    static final int LIMITE = 10;
    static final long JANELA_MS = 60_000;
    static final String ROTA_LOGIN = "/api/auth/login";

    // ponytail: contagem em memória, por instância e por IP direto (getRemoteAddr). Atrás de proxy/várias instâncias,
    // trocar por X-Forwarded-For confiável + Bucket4j/Redis.
    private final Map<String, long[]> tentativas = new ConcurrentHashMap<>(); // ip -> {inicioJanela, contagem}

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod())
                && request.getRequestURI().equals(request.getContextPath() + ROTA_LOGIN));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long agora = System.currentTimeMillis();
        if (tentativas.size() > 10_000) {
            tentativas.clear(); // teto de memória contra varredura de IPs
        }
        long[] janela = tentativas.compute(request.getRemoteAddr(), (ip, atual) ->
                atual == null || agora - atual[0] >= JANELA_MS
                        ? new long[]{agora, 1}
                        : new long[]{atual[0], atual[1] + 1});

        if (janela[1] > LIMITE) {
            long segundos = Math.max(1, (JANELA_MS - (agora - janela[0]) + 999) / 1000);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(segundos));
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("""
                    {"type":"about:blank","title":"Muitas tentativas","status":429,\
                    "detail":"Limite de tentativas de login excedido! Tente novamente em %d segundos."}"""
                    .formatted(segundos));
            return;
        }
        chain.doFilter(request, response);
    }
}
