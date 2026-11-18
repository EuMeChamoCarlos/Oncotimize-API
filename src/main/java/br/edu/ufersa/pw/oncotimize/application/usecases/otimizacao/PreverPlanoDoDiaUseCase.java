package br.edu.ufersa.pw.oncotimize.application.usecases.otimizacao;

import br.edu.ufersa.pw.oncotimize.domain.exception.EstoqueInsuficienteException;
import br.edu.ufersa.pw.oncotimize.domain.medicamento.Medicamento;
import br.edu.ufersa.pw.oncotimize.domain.medicamento.MedicamentoRepository;
import br.edu.ufersa.pw.oncotimize.domain.otimizacao.Otimizacao;
import br.edu.ufersa.pw.oncotimize.domain.prescricao.PrescricaoRepository;
import br.edu.ufersa.pw.oncotimize.domain.prescricao.StatusPrescricao;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Caso de uso: prévia do plano do dia para todos os medicamentos com paciente APTO, sem gravar nada (painel do
 * plano do dia). Usa o mesmo cálculo do {@link OtimizarDiaUseCase}; o farmacêutico grava o plano pelo POST.
 */
@Service
public class PreverPlanoDoDiaUseCase {

    /** plano nulo quando o dia não tem como ser atendido; problema diz por quê (hoje, só estoque insuficiente). */
    public record PlanoPrevio(UUID medicamentoId, Otimizacao plano, String problema) {}

    private final MedicamentoRepository medicamentoRepository;
    private final PrescricaoRepository prescricaoRepository;
    private final OtimizarDiaUseCase otimizarDia;

    public PreverPlanoDoDiaUseCase(MedicamentoRepository medicamentoRepository,
                                   PrescricaoRepository prescricaoRepository, OtimizarDiaUseCase otimizarDia) {
        this.medicamentoRepository = medicamentoRepository;
        this.prescricaoRepository = prescricaoRepository;
        this.otimizarDia = otimizarDia;
    }

    // ponytail: uma instância do solver por medicamento, em sequência; paralelizar se o dia tiver dezenas de medicamentos
    @Transactional(readOnly = true)
    public List<PlanoPrevio> executar(LocalDate data) {
        return medicamentoRepository.findAll().stream()
                .sorted(Comparator.comparing(Medicamento::getNome))
                .filter(m -> !prescricaoRepository.buscarDoDia(m.getId(), data, StatusPrescricao.APTO).isEmpty())
                .map(m -> {
                    try {
                        return new PlanoPrevio(m.getId(), otimizarDia.montar(m.getId(), data), null);
                    } catch (EstoqueInsuficienteException e) {
                        return new PlanoPrevio(m.getId(), null, e.getMessage());
                    }
                })
                .toList();
    }
}
