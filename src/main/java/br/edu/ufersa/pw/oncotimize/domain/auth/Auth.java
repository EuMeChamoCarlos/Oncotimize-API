package br.edu.ufersa.pw.oncotimize.domain.auth;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "auths")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Auth {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(unique = true, nullable = false)
    private String username;

    @JsonIgnore
    @Column(nullable = false)
    private String password;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** A senha chega já codificada (AuthService); a entidade nunca vê a senha em texto puro. */
    public Auth(String username, String senhaCodificada) {
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(senhaCodificada, "senhaCodificada");
    }
}