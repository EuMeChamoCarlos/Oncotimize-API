package br.edu.ufersa.pw.oncotimize.application.usecases.otimizacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import br.edu.ufersa.pw.oncotimize.application.api.prescricao.dto.PrescricaoRequest;
import br.edu.ufersa.pw.oncotimize.application.usecases.otimizacao.PreverPlanoDoDiaUseCase.PlanoPrevio;
import br.edu.ufersa.pw.oncotimize.application.usecases.prescricao.CriarPrescricaoUseCase;
import br.edu.ufersa.pw.oncotimize.domain.frascos.Frascos;
import br.edu.ufersa.pw.oncotimize.domain.frascos.FrascosRepository;
import br.edu.ufersa.pw.oncotimize.domain.medicamento.Medicamento;
import br.edu.ufersa.pw.oncotimize.domain.medicamento.MedicamentoRepository;
import br.edu.ufersa.pw.oncotimize.domain.otimizacao.Otimizacao;
import br.edu.ufersa.pw.oncotimize.domain.otimizacao.OtimizacaoRepository;
import br.edu.ufersa.pw.oncotimize.domain.prescricao.StatusPrescricao;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

/**
 * Painel do plano do dia: prévia de todos os medicamentos sem gravar e listagem dos planos do dia sem filtro.
 * A: frasco 300 mg R$ 190, doses 260 + 30 → um frasco compartilhado (sem compartilhar, 380). B: um frasco de 100 mg
 * em estoque para 250 mg → estoque insuficiente. C: sem paciente no dia → fora da prévia.
 * Integração com o Postgres local; @Transactional desfaz tudo; datas em 2031 e códigos 9999.
 */
@SpringBootTest
@Transactional
class PlanoDoDiaPainelTest {

    private static final LocalDate DIA = LocalDate.of(2031, 2, 10);

    @Autowired private MedicamentoRepository medicamentoRepository;
    @Autowired private FrascosRepository frascosRepository;
    @Autowired private OtimizacaoRepository otimizacaoRepository;
    @Autowired private CriarPrescricaoUseCase criarPrescricao;
    @Autowired private PreverPlanoDoDiaUseCase preverPlanoDoDia;
    @Autowired private OtimizarDiaUseCase otimizarDia;
    @Autowired private BuscarOtimizacaoUseCase buscarOtimizacao;

    private Medicamento a;
    private Medicamento b;
    private Medicamento c;

    @BeforeEach
    void cenario() {
        a = medicamentoRepository.save(Medicamento.builder("Teste painel A").build());
        b = medicamentoRepository.save(Medicamento.builder("Teste painel B").build());
        c = medicamentoRepository.save(Medicamento.builder("Teste painel C").build());
        frascosRepository.save(new Frascos(a, 300.0, 190.0, 20, null));
        frascosRepository.save(new Frascos(b, 100.0, 50.0, 1, null));
        frascosRepository.save(new Frascos(c, 100.0, 50.0, 20, null));
        prescricao("PRE9999-000060", a, 260.0);
        prescricao("PRE9999-000061", a, 30.0);
        prescricao("PRE9999-000062", b, 250.0);
    }

    @Test
    void previaTrazCadaMedicamentoDoDiaSemGravarEBateComOPlanoGerado() {
        long planosAntes = otimizacaoRepository.count();

        List<PlanoPrevio> previa = doTeste(preverPlanoDoDia.executar(DIA));

        assertThat(otimizacaoRepository.count()).isEqualTo(planosAntes);
        assertThat(previa).extracting(PlanoPrevio::medicamentoId).containsExactly(a.getId(), b.getId());

        PlanoPrevio deA = previa.get(0);
        assertThat(deA.problema()).isNull();
        assertThat(deA.plano().getId()).isNull();
        assertThat(deA.plano().getCustoTotal().doubleValue()).isCloseTo(190, within(1e-6));
        assertThat(deA.plano().getEconomiaVsEmpirico().doubleValue()).isCloseTo(190, within(1e-6));
        assertThat(deA.plano().getAlocacoes()).hasSize(2);

        PlanoPrevio deB = previa.get(1);
        assertThat(deB.plano()).isNull();
        assertThat(deB.problema()).containsIgnoringCase("estoque");

        Otimizacao gerado = otimizarDia.executar(a.getId(), DIA);
        assertThat(gerado.getCustoTotal()).isEqualByComparingTo(deA.plano().getCustoTotal());
        assertThat(gerado.getEconomiaVsEmpirico()).isEqualByComparingTo(deA.plano().getEconomiaVsEmpirico());
    }

    @Test
    void listaSemMedicamentoTrazOsPlanosDoDiaDeTodos() {
        prescricao("PRE9999-000063", c, 80.0);
        Otimizacao deA = otimizarDia.executar(a.getId(), DIA);
        Otimizacao deC = otimizarDia.executar(c.getId(), DIA);

        assertThat(buscarOtimizacao.listar(null, DIA, Pageable.unpaged()).getContent())
                .extracting(Otimizacao::getId)
                .contains(deA.getId(), deC.getId());
        assertThat(buscarOtimizacao.listar(null, DIA.plusDays(1), Pageable.unpaged()).getContent())
                .extracting(Otimizacao::getId)
                .doesNotContain(deA.getId(), deC.getId());
    }

    /** Só os medicamentos deste teste (o banco de dev tem outros). */
    private List<PlanoPrevio> doTeste(List<PlanoPrevio> previa) {
        Set<UUID> meus = Set.of(a.getId(), b.getId(), c.getId());
        return previa.stream().filter(p -> meus.contains(p.medicamentoId())).toList();
    }

    private void prescricao(String codigo, Medicamento m, double dose) {
        criarPrescricao.executar(new PrescricaoRequest(codigo, m.getId(), dose, StatusPrescricao.APTO, DIA, null));
    }
}
