package br.edu.ufersa.pw.oncotimize.domain.otimizacao;

import br.edu.ufersa.pw.oncotimize.domain.exception.EstadoInvalidoException;
import br.edu.ufersa.pw.oncotimize.domain.exception.FrascoInvalidoException;
import br.edu.ufersa.pw.oncotimize.domain.frascos.Frascos;
import br.edu.ufersa.pw.oncotimize.domain.medicamento.Medicamento;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Invariantes das entidades que perderam os setters (Otimizacao, AlocacaoFrasco, Frascos). Sem banco. */
class OtimizacaoTest {

    private final Medicamento paclitaxel = Medicamento.builder("Paclitaxel").build();

    private Otimizacao plano() {
        return new Otimizacao(paclitaxel, LocalDate.of(2027, 3, 1), new BigDecimal("190.0000"), 10.0,
                new BigDecimal("25.0000"));
    }

    @Test
    void planoNasceConcluidoESemAlocacoesAlteraveisPorFora() {
        Otimizacao plano = plano();
        assertThat(plano.getStatus()).isEqualTo(StatusOtimizacao.CONCLUIDA);
        assertThatThrownBy(() -> plano.getAlocacoes().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void planoRecusadoNaoSeConfirma() {
        Otimizacao plano = plano();
        plano.recusar("admin@oncotimize.com", "teste");
        assertThatThrownBy(plano::confirmar)
                .isInstanceOf(EstadoInvalidoException.class)
                .hasMessageContaining("recusado");
    }

    @Test
    void alocacaoRejeitaFracaoForaDoIntervalo() {
        Frascos frasco = new Frascos(paclitaxel, 300.0, 120.0, 5, null);
        assertThatThrownBy(() -> plano().adicionarAlocacao(null, frasco, 1, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plano().adicionarAlocacao(null, frasco, 1, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void frascoRejeitaVolumeCustoEEstoqueInvalidos() {
        assertThatThrownBy(() -> new Frascos(paclitaxel, 0.0, 120.0, 5, null))
                .isInstanceOf(FrascoInvalidoException.class);
        assertThatThrownBy(() -> new Frascos(paclitaxel, 300.0, null, 5, null))
                .isInstanceOf(FrascoInvalidoException.class);
        Frascos frasco = new Frascos(paclitaxel, 300.0, 120.0, 5, null);
        assertThatThrownBy(() -> frasco.ajustarEstoque(-1))
                .isInstanceOf(FrascoInvalidoException.class);
        assertThat(frasco.getQuantidadeEstoque()).isEqualTo(5);
    }
}
