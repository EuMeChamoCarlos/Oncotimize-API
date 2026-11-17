package br.edu.ufersa.pw.oncotimize.domain.auth;

import br.edu.ufersa.pw.oncotimize.domain.exception.RecursoDuplicadoException;
import br.edu.ufersa.pw.oncotimize.domain.exception.RecursoNaoEncontradoException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuthService {

    private final AuthRepository authRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(AuthRepository authRepository, PasswordEncoder passwordEncoder) {
        this.authRepository = authRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public Auth createAuth(String username, String rawPassword) {
        if (authRepository.existsByUsername(username)) {
            throw new RecursoDuplicadoException("Auth", "username", username);
        }

        return authRepository.save(new Auth(username, passwordEncoder.encode(rawPassword)));
    }

    // Buscar credenciais por nome de usuário
    public Auth getByUsername(String username) {
        return authRepository.findByUsername(username)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Usuário", username));
    }

    public Auth getById(UUID id) {
        return authRepository.findById(id)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Credenciais", id));
    }

    public void deleteById(UUID id) {
        if (!authRepository.existsById(id)) {
            throw new RecursoNaoEncontradoException("Credenciais", id);
        }
        authRepository.deleteById(id);
    }

    public boolean usernameExists(String username) {
        return authRepository.existsByUsername(username);
    }
}
