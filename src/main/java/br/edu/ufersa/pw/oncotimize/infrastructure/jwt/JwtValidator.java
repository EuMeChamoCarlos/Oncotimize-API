package br.edu.ufersa.pw.oncotimize.infrastructure.jwt;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Component
public class JwtValidator {

    private final SecretKey chave;

    public JwtValidator(@Value("${api.security.token.secret}") String secretKey) {
        this.chave = chaveHmac(secretKey);
    }

    /**
     * Valida o segredo na subida da aplicação (vazio ou com menos de 32 bytes derruba o boot),
     * em vez de estourar 500 só no primeiro login. Usado também pelo JwtGenerator.
     */
    static SecretKey chaveHmac(String secretKey) {
        if (!StringUtils.hasText(secretKey)) {
            throw new IllegalStateException("Missing required configuration: api.security.token.secret");
        }
        byte[] bytes = secretKey.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("api.security.token.secret precisa de no mínimo 32 bytes (HS256)");
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    public Claims validateTokenAndGetClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(chave)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new ExpiredJwtException(e.getHeader(), e.getClaims(), "Token expirado");
        } catch (JwtException e) {
            throw new JwtException("Token inválido");
        }
    }

}
