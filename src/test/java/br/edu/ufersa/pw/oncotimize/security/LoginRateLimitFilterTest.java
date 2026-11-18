package br.edu.ufersa.pw.oncotimize.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.edu.ufersa.pw.oncotimize.infrastructure.jwt.JwtGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LoginRateLimitFilterTest {

    private final LoginRateLimitFilter filtro = new LoginRateLimitFilter();

    private int status(String metodo, String uri, String ip) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(metodo, uri);
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filtro.doFilter(req, res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    void bloqueiaLoginDepoisDoLimitePorIp() throws Exception {
        for (int i = 0; i < LoginRateLimitFilter.LIMITE; i++) {
            assertThat(status("POST", "/api/auth/login", "10.0.0.1")).isEqualTo(200);
        }
        assertThat(status("POST", "/api/auth/login", "10.0.0.1")).isEqualTo(429);
        // outro IP e outras rotas não são afetados
        assertThat(status("POST", "/api/auth/login", "10.0.0.2")).isEqualTo(200);
        assertThat(status("GET", "/api/medicamentos", "10.0.0.1")).isEqualTo(200);
    }

    @Test
    void segredoJwtCurtoDerrubaNaSubida() {
        assertThatThrownBy(() -> new JwtGenerator("curto-demais"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
