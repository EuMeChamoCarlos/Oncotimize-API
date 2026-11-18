package br.edu.ufersa.pw.oncotimize.application.usecases.agenda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import br.edu.ufersa.pw.oncotimize.application.api.prescricao.dto.PrescricaoRequest;
import br.edu.ufersa.pw.oncotimize.application.usecases.otimizacao.ConfirmarOtimizacaoUseCase;
import br.edu.ufersa.pw.oncotimize.application.usecases.otimizacao.OtimizarDiaUseCase;
import br.edu.ufersa.pw.oncotimize.application.usecases.otimizacao.RecusarOtimizacaoUseCase;
import br.edu.ufersa.pw.oncotimize.application.usecases.prescricao.CriarPrescricaoUseCase;
import br.edu.ufersa.pw.oncotimize.domain.agenda.SugestaoRemarcacao;
import br.edu.ufersa.pw.oncotimize.domain.agenda.SugestaoRemarcacaoRepository;
import br.edu.ufersa.pw.oncotimize.application.usecases.protocolo.ListarAplicacoesDoDiaUseCase;
import java.math.BigDecimal;
import org.springframework.data.domain.Pageable;
import br.edu.ufersa.pw.oncotimize.domain.exception.EstadoInvalidoException;
import br.edu.ufersa.pw.oncotimize.domain.exception.RecursoNaoEncontradoException;
import br.edu.ufersa.pw.oncotimize.domain.exception.SemDiaDeRetornoException;
import br.edu.ufersa.pw.oncotimize.domain.protocolo.StatusAplicacao;
import br.edu.ufersa.pw.oncotimize.domain.frascos.Frascos;
import br.edu.ufersa.pw.oncotimize.domain.frascos.FrascosRepository;
import br.edu.ufersa.pw.oncotimize.domain.medicamento.Medicamento;
import br.edu.ufersa.pw.oncotimize.domain.medicamento.MedicamentoRepository;
import br.edu.ufersa.pw.oncotimize.domain.otimizacao.Otimizacao;
import br.edu.ufersa.pw.oncotimize.domain.otimizacao.StatusOtimizacao;
import br.edu.ufersa.pw.oncotimize.domain.prescricao.Prescricao;
import br.edu.ufersa.pw.oncotimize.domain.prescricao.StatusPrescricao;
import br.edu.ufersa.pw.oncotimize.domain.protocolo.Aplicacao;
import br.edu.ufersa.pw.oncotimize.domain.protocolo.ProtocoloPaciente;
import br.edu.ufersa.pw.oncotimize.domain.protocolo.ProtocoloPaciente.MedicamentoNovo;
import br.edu.ufersa.pw.oncotimize.domain.protocolo.ProtocoloPacienteRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integração com o Postgres local. Paciente não apto em 03/03/2031 (Paclitaxel 175 mg) pode voltar a partir de
 * 10/03; em 12/03 há outro paciente de 260 mg. Frascos 300 mg US$ 190 e 100 mg US$ 70, 30 mg US$ 25.
 * Sozinho, 175 mg custa US$ 140 (2×100); junto com 260 mg, o dia passa de 190 para 310 (300+100+30+30):
 * custo marginal 120, economia 20. @Transactional desfaz tudo; datas em 2031 e códigos 9999.
 */
@SpringBootTest
@Transactional
class FluxoRemarcacaoTest {

    private static final LocalDate ORIGEM = LocalDate.of(2031, 3, 3);
    private static final LocalDate MINIMA = LocalDate.of(2031, 3, 10);
    private static final LocalDate GRUPO = LocalDate.of(2031, 3, 12);

    @Autowired private MedicamentoRepository medicamentoRepository;
    @Autowired private FrascosRepository frascosRepository;
    @Autowired private ProtocoloPacienteRepository protocoloRepository;
    @Autowired private SugerirRemarcacaoNaoAptoUseCase sugerirRemarcacao;
    @Autowired private AceitarSugestaoUseCase aceitarSugestao;
    @Autowired private RecusarSugestaoUseCase recusarSugestao;
    @Autowired private CriarPrescricaoUseCase criarPrescricao;
    @Autowired private SugestaoRemarcacaoRepository sugestaoRepository;
    @Autowired private ListarAplicacoesDoDiaUseCase listarAplicacoesDoDia;
    @Autowired private PreverSugestoesUseCase preverSugestoes;
    @Autowired private GerarSugestoesUseCase gerarSugestoes;
    @Autowired private OtimizarDiaUseCase otimizarDia;
    @Autowired private RecusarOtimizacaoUseCase recusarOtimizacao;
    @Autowired private ConfirmarOtimizacaoUseCase confirmarOtimizacao;

    private Medicamento pac;
    private Aplicacao naoApto;
    private Aplicacao doGrupo;

    @BeforeEach
    void cenario() {
        pac = medicamentoRepository.save(Medicamento.builder("Teste remarcação pac").build());
        frasco(300, 190);
        frasco(100, 70);
        frasco(30, 25);
        naoApto = protocolo("PTC9999-000040", ORIGEM, 175.0).getAplicacoes().get(0);
        doGrupo = protocolo("PTC9999-000041", GRUPO, 260.0).getAplicacoes().get(0);
    }

    @Test
    void naoAptoVoltaNoDiaQueDivideFrascoEAceiteRemarca() {
        SugestaoRemarcacao s = sugerirRemarcacao.executar(naoApto.getId(), MINIMA, "imunidade baixa");

        assertThat(s.getTipo()).isEqualTo(SugestaoRemarcacao.Tipo.NAO_APTO);
        assertThat(s.getDataOrigem()).isEqualTo(ORIGEM);
        assertThat(s.getDataDestino()).isEqualTo(GRUPO);
        assertThat(s.getEconomiaEstimada().doubleValue()).isCloseTo(20, within(1e-6));
        assertThat(naoApto.getDataAgendada()).isEqualTo(ORIGEM);

        aceitarSugestao.executar(s.getId(), "enfermeira@teste", null);

        // A agenda do dia (GET /api/aplicacoes?data=) já mostra a aplicação no dia novo e não mais no de origem.
        assertThat(listarAplicacoesDoDia.executar(GRUPO, Pageable.unpaged()).getContent()).contains(naoApto);
        assertThat(listarAplicacoesDoDia.executar(ORIGEM, Pageable.unpaged()).getContent()).doesNotContain(naoApto);
        assertThat(s.getStatus()).isEqualTo(SugestaoRemarcacao.Status.ACEITA);
        assertThat(s.getDecididaPor()).isEqualTo("enfermeira@teste");
        assertThat(naoApto.getDataAgendada()).isEqualTo(GRUPO);
        assertThat(naoApto.getDataPrevista()).isEqualTo(ORIGEM);
        assertThat(naoApto.getMotivoAlteracao()).contains("imunidade baixa");
        assertThatThrownBy(() -> recusarSugestao.executar(s.getId(), "enfermeira@teste", null))
                .isInstanceOf(EstadoInvalidoException.class).hasMessageContaining("ACEITA");
    }

    /** Retorno de não apto aceito fica fixo para a agenda: o planejador não o devolve ao dia em que estava inapto. */
    @Test
    void agendaNaoMoveORetornoDeNaoApto() {
        Medicamento outro = medicamentoRepository.save(Medicamento.builder("Teste retorno fixo").build());
        frascosRepository.save(new Frascos(outro, 300.0, 190.0, 20, null));
        frascosRepository.save(new Frascos(outro, 100.0, 70.0, 20, null));
        frascosRepository.save(new Frascos(outro, 30.0, 25.0, 20, null));
        // X (175 mg) tem janela de ±2 dias; Y (260 mg) fica em 03/03. Separados custam 140 + 190 = 330; juntos, 310.
        // Sem a trava, a agenda levaria X de volta a 03/03, o dia em que ele estava inapto.
        Aplicacao x = protocoloRepository.save(ProtocoloPaciente.novo("PTC9999-000046", "TAX", null, ORIGEM, 1, 21, 0, 2, 2,
                List.of(new MedicamentoNovo(outro, 175.0, "IV", "D1")))).getAplicacoes().get(0);
        protocoloRepository.save(ProtocoloPaciente.novo("PTC9999-000047", "TAX", null, ORIGEM, 1, 21, 0, 0, 0,
                List.of(new MedicamentoNovo(outro, 260.0, "IV", "D1"))));

        SugestaoRemarcacao volta = sugerirRemarcacao.executar(x.getId(), ORIGEM.plusDays(1), "imunidade baixa");
        aceitarSugestao.executar(volta.getId(), "enfermeira@teste", null);
        assertThat(x.getDataAgendada()).isEqualTo(ORIGEM.plusDays(1));

        assertThat(preverSugestoes.executar(outro.getId(), ORIGEM.minusDays(2), ORIGEM.plusDays(3)).sugestoes())
                .noneMatch(s -> s.aplicacaoId().equals(x.getId()));
    }

    /**
     * Busca sem medicamento: todos de uma vez. A aplicação X (A 30 mg + B 100 mg, janela +1) cabe nas sobras do dia
     * seguinte (A: 260 de 300; B: 900 de 1000). Sozinha custa 190 + 100; junto, zero a mais: uma sugestão só, ganho 290.
     */
    @Test
    void buscaEmTodosOsMedicamentosSugereAAplicacaoUmaVezSo() {
        Medicamento a = medicamentoRepository.save(Medicamento.builder("Teste todos A").build());
        Medicamento b = medicamentoRepository.save(Medicamento.builder("Teste todos B").build());
        frascosRepository.save(new Frascos(a, 300.0, 190.0, 20, null));
        frascosRepository.save(new Frascos(b, 1000.0, 100.0, 20, null));
        LocalDate dia = LocalDate.of(2031, 6, 2);
        Aplicacao x = protocoloRepository.save(ProtocoloPaciente.novo("PTC9999-000050", "AC", null, dia, 1, 21, 0, 0, 1,
                List.of(new MedicamentoNovo(a, 30.0, "IV", "D1"), new MedicamentoNovo(b, 100.0, "IV", "D1"))))
                .getAplicacoes().get(0);
        protocoloRepository.save(ProtocoloPaciente.novo("PTC9999-000051", "TAX", null, dia.plusDays(1), 1, 21, 0, 0, 0,
                List.of(new MedicamentoNovo(a, 260.0, "IV", "D1"))));
        protocoloRepository.save(ProtocoloPaciente.novo("PTC9999-000052", "CICLO", null, dia.plusDays(1), 1, 21, 0, 0, 0,
                List.of(new MedicamentoNovo(b, 900.0, "IV", "D1"))));

        List<SugestaoRemarcacao> sugestoes = gerarSugestoes.executar(null, dia, dia.plusDays(6));

        assertThat(sugestoes).singleElement().satisfies(s -> {
            assertThat(s.getAplicacao().getId()).isEqualTo(x.getId());
            assertThat(s.getDataDestino()).isEqualTo(dia.plusDays(1));
            assertThat(s.getEconomiaEstimada().doubleValue()).isCloseTo(290, within(1e-6));
        });
    }

    /** Lacuna (b): registrar o não apto tira o paciente da otimização do dia, sem esperar o aceite da sugestão. */
    @Test
    void naoAptoSaiDaOtimizacaoDoDiaEFicaRegistradoNoDiaDaAvaliacao() {
        // Emitida na véspera, para a aplicação de 03/03: a otimização de 03/03 conta o paciente.
        Prescricao linha = criarPrescricao.executar(new PrescricaoRequest("PRE9999-000042", pac.getId(), 175.0,
                StatusPrescricao.APTO, ORIGEM.minusDays(1), naoApto.getId()));
        assertThat(otimizarDia.executar(pac.getId(), ORIGEM).getAlocacoes()).isNotEmpty();

        sugerirRemarcacao.executar(naoApto.getId(), MINIMA, "imunidade baixa");

        assertThat(linha.getStatus()).isEqualTo(StatusPrescricao.NAO_APTO);
        assertThat(linha.getDataPrescricao()).isEqualTo(ORIGEM.minusDays(1)); // a data de emissão não muda
        assertThat(linha.getDoseMg()).isEqualTo(175.0);
        assertThatThrownBy(() -> otimizarDia.executar(pac.getId(), ORIGEM))
                .isInstanceOf(RecursoNaoEncontradoException.class);
    }

    /** O plano gerado antes do não apto não pode mais ser confirmado: daria baixa no frasco de quem não está apto. */
    @Test
    void planoGeradoAntesDoNaoAptoNaoPodeSerConfirmado() {
        criarPrescricao.executar(new PrescricaoRequest("PRE9999-000044", pac.getId(), 175.0, StatusPrescricao.APTO,
                ORIGEM, naoApto.getId()));
        Otimizacao plano = otimizarDia.executar(pac.getId(), ORIGEM);

        sugerirRemarcacao.executar(naoApto.getId(), MINIMA, "imunidade baixa");

        assertThatThrownBy(() -> confirmarOtimizacao.executar(plano.getId()))
                .isInstanceOf(EstadoInvalidoException.class).hasMessageContaining("não está mais apta");
        assertThat(naoApto.getStatus()).isEqualTo(StatusAplicacao.AGENDADA);
    }

    /** Sem dia de retorno possível, o 422 sai, mas o não apto fica registrado (é decisão médica). */
    @Test
    void semDiaDeRetornoONaoAptoFicaRegistrado() {
        // Três aplicações a cada 7 dias com intervalo mínimo de 7: nenhum dia entre 04/03 e 18/03 serve.
        Aplicacao primeira = protocoloRepository.save(ProtocoloPaciente.novo("PTC9999-000045", "TAX W", null, ORIGEM, 3, 7,
                7, 0, 0, List.of(new MedicamentoNovo(pac, 80.0, "IV", "D1")))).getAplicacoes().get(0);
        Prescricao linha = criarPrescricao.executar(new PrescricaoRequest("PRE9999-000045", pac.getId(), 80.0,
                StatusPrescricao.APTO, ORIGEM, primeira.getId()));
        // Sugestão antiga da agenda ainda pendente: aceita depois, marcaria o retorno antes da data mínima.
        SugestaoRemarcacao antiga = sugestaoRepository.save(
                SugestaoRemarcacao.daAgenda(primeira, ORIGEM.plusDays(1), BigDecimal.TEN));

        assertThatThrownBy(() -> sugerirRemarcacao.executar(primeira.getId(), ORIGEM.plusDays(1), "imunidade baixa"))
                .isInstanceOf(SemDiaDeRetornoException.class).hasMessageContaining("registrado como não apto");
        assertThat(linha.getStatus()).isEqualTo(StatusPrescricao.NAO_APTO);
        assertThat(antiga.getStatus()).isEqualTo(SugestaoRemarcacao.Status.EXPIRADA);
    }

    /** No AC, um medicamento com preparo confirmado no dia: o frasco já foi aberto, o não apto não cabe mais. */
    @Test
    void naoAptoComPreparoJaConfirmadoNoDiaERecusado() {
        Medicamento ciclo = medicamentoRepository.save(Medicamento.builder("Teste remarcação ciclo").build());
        frascosRepository.save(new Frascos(ciclo, 1000.0, 30.0, 20, null));
        Aplicacao ac = protocoloRepository.save(ProtocoloPaciente.novo("PTC9999-000042", "AC", null, ORIGEM, 1, 21, 0, 0, 0,
                List.of(new MedicamentoNovo(pac, 100.0, "IV", "D1"), new MedicamentoNovo(ciclo, 1000.0, "IV", "D1"))))
                .getAplicacoes().get(0);
        criarPrescricao.executar(new PrescricaoRequest("PRE9999-000043", pac.getId(), 100.0, StatusPrescricao.APTO, ORIGEM,
                ac.getId()));
        Prescricao linhaCiclo = criarPrescricao.executar(new PrescricaoRequest("PRE9999-000043", ciclo.getId(), 1000.0,
                StatusPrescricao.APTO, ORIGEM, ac.getId()));
        confirmarOtimizacao.executar(otimizarDia.executar(pac.getId(), ORIGEM).getId());

        assertThatThrownBy(() -> sugerirRemarcacao.executar(ac.getId(), MINIMA, "imunidade baixa"))
                .isInstanceOf(EstadoInvalidoException.class).hasMessageContaining("preparo confirmado");
        assertThat(linhaCiclo.getStatus()).isEqualTo(StatusPrescricao.APTO);
    }

    @Test
    void recusaGuardaQuemEMotivoENaoMudaAData() {
        SugestaoRemarcacao s = sugerirRemarcacao.executar(naoApto.getId(), MINIMA, "imunidade baixa");

        recusarSugestao.executar(s.getId(), "enfermeira@teste", "paciente pediu outro dia");

        assertThat(s.getStatus()).isEqualTo(SugestaoRemarcacao.Status.RECUSADA);
        assertThat(s.getMotivoDecisao()).isEqualTo("paciente pediu outro dia");
        assertThat(naoApto.getDataAgendada()).isEqualTo(ORIGEM);
    }

    @Test
    void novaSugestaoExpiraAPendenteAnterior() {
        SugestaoRemarcacao primeira = sugerirRemarcacao.executar(naoApto.getId(), MINIMA, "imunidade baixa");
        sugerirRemarcacao.executar(naoApto.getId(), MINIMA.plusDays(1), "imunidade baixa");

        assertThat(primeira.getStatus()).isEqualTo(SugestaoRemarcacao.Status.EXPIRADA);
    }

    @Test
    void planoRecusadoNaoPodeSerConfirmado() {
        criarPrescricao.executar(new PrescricaoRequest("PRE9999-000041", pac.getId(), 260.0, StatusPrescricao.APTO, GRUPO,
                doGrupo.getId()));
        Otimizacao plano = otimizarDia.executar(pac.getId(), GRUPO);

        recusarOtimizacao.executar(plano.getId(), "farmaceutico@teste", "frasco com lote vencendo");

        assertThat(plano.getStatus()).isEqualTo(StatusOtimizacao.RECUSADA);
        assertThat(plano.getRecusadaPor()).isEqualTo("farmaceutico@teste");
        assertThat(plano.getMotivoRecusa()).isEqualTo("frasco com lote vencendo");
        assertThatThrownBy(() -> confirmarOtimizacao.executar(plano.getId()))
                .isInstanceOf(EstadoInvalidoException.class).hasMessageContaining("recusado");
    }

    private ProtocoloPaciente protocolo(String codigo, LocalDate inicio, double dose) {
        return protocoloRepository.save(ProtocoloPaciente.novo(codigo, "TAX", null, inicio, 1, 21, 0, 0, 0,
                List.of(new MedicamentoNovo(pac, dose, "IV", "D1"))));
    }

    private void frasco(double volume, double custo) {
        frascosRepository.save(new Frascos(pac, volume, custo, 20, null));
    }
}
