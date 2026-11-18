package br.edu.ufersa.pw.oncotimize.infrastructure.jwt;

import java.util.Date;
import java.util.Map;
import javax.crypto.SecretKey;

import br.edu.ufersa.pw.oncotimize.domain.user.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Jwts;

@Component
public class JwtGenerator {

    static final long EXPIRATION_TIME = (1000 * 60 * 60 * 10); // 10 horas

    private final SecretKey chave;

    public JwtGenerator(@Value("${api.security.token.secret}") String secretKey) {
        this.chave = JwtValidator.chaveHmac(secretKey);
    }

    public String generateToken(Authentication authentication) {
        var user = (User) authentication.getPrincipal();

        return Jwts.builder()
                .subject(user.getAuth().getUsername())
                .claims(
                    Map.of(
                        "UUID", user.getId(),
                        "ROLE", user.getRole().getName(),
                        "name", user.getProfile() == null
                                ? "Usuário Indefinido"
                                : user.getProfile().getName()
                    )
                )
                .issuedAt(new Date(System.currentTimeMillis())) // Data de emissão
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME)) // Expira em 10 horas
                .signWith(chave)
                .compact();
    }
}
