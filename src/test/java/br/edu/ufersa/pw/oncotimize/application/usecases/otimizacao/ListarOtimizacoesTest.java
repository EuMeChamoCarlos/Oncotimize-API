package br.edu.ufersa.pw.oncotimize.application.usecases.otimizacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.edu.ufersa.pw.oncotimize.application.api.prescricao.dto.PrescricaoRequest;
import br.edu.ufersa.pw.oncotimize.application.usecases.prescricao.CriarPrescricaoUseCase;
import br.edu.ufersa.pw.oncotimize.domain.exception.RecursoDuplicadoException;
import br.edu.ufersa.pw.oncotimize.domain.frascos.Frascos;
import br.edu.ufersa.pw.oncotimize.domain.frascos.FrascosRepository;
import br.edu.ufersa.pw.oncotimize.domain.medicamento.Medicamento;
import br.edu.ufersa.pw.oncotimize.domain.medicamento.MedicamentoRepository;
import br.edu.ufersa.pw.oncotimize.domain.otimizacao.Otimizacao;
import br.edu.ufersa.pw.oncotimize.domain.prescricao.StatusPrescricao;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integração com o Postgres local: listagem dos planos do dia (usada pelo front) e a trava do POST depois do
 * preparo confirmado. @Transactional desfaz tudo; datas em 2031 e códigos 9999 para não cruzar com o seed.
 */
@SpringBootTest
@Transactional
class ListarOtimizacoesTest {

    private static final LocalDate DIA = LocalDate.of(2031, 2, 3);
    private static final PageRequest MAIS_RECENTE_PRIMEIRO = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

    @Autowired private MedicamentoRepository medicamentoRepository;
    @Autowired private FrascosRepository frascosRepository;
    @Autowired private CriarPrescricaoUseCase criarPrescricao;
    @Autowired private OtimizarDiaUseCase otimizarDia;
    @Autowired private ConfirmarOtimizacaoUseCase confirmarOtimizacao;
    @Autowired private BuscarOtimizacaoUseCase buscarOtimizacao;

    private Medicamento medicamento;

    @BeforeEach
    void cenario() {
        medicamento = medicamentoRepository.save(Medicamento.builder("Teste listagem planos").build());
        frascosRepository.save(new Frascos(medicamento, 100.0, 50.0, 20, null));
        prescricao("PRE9999-000040", DIA);
        prescricao("PRE9999-000041", DIA.plusDays(1));
    }

    @Test
    void listaSoOsPlanosDoMedicamentoNoDiaComOMaisRecentePrimeiro() {
        Otimizacao primeiro = otimizarDia.executar(medicamento.getId(), DIA);
        Otimizacao segundo = otimizarDia.executar(medicamento.getId(), DIA);
        otimizarDia.executar(medicamento.getId(), DIA.plusDays(1));

        assertThat(buscarOtimizacao.listar(medicamento.getId(), DIA, MAIS_RECENTE_PRIMEIRO).getContent())
                .extracting(Otimizacao::getId)
                .containsExactly(segundo.getId(), primeiro.getId());
    }

    @Test
    void diaSemPlanoDevolveListaVazia() {
        assertThat(buscarOtimizacao.listar(medicamento.getId(), DIA, MAIS_RECENTE_PRIMEIRO)).isEmpty();
    }

    @Test
    void naoGeraPlanoNovoDepoisDoPreparoConfirmado() {
        Otimizacao plano = otimizarDia.executar(medicamento.getId(), DIA);
        confirmarOtimizacao.executar(plano.getId());

        assertThatThrownBy(() -> otimizarDia.executar(medicamento.getId(), DIA))
                .isInstanceOf(RecursoDuplicadoException.class)
                .hasMessageContaining("Plano confirmado");
        // Outro dia do mesmo medicamento continua livre.
        assertThat(otimizarDia.executar(medicamento.getId(), DIA.plusDays(1)).getId()).isNotNull();
    }

    private void prescricao(String codigo, LocalDate data) {
        criarPrescricao.executar(new PrescricaoRequest(codigo, medicamento.getId(), 80.0, StatusPrescricao.APTO, data, null));
    }
}
