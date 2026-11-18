package br.edu.ufersa.pw.oncotimize.application.api.otimizacao.dto;

import br.edu.ufersa.pw.oncotimize.application.usecases.otimizacao.PreverPlanoDoDiaUseCase.PlanoPrevio;
import java.util.UUID;

/**
 * Prévia de um medicamento no dia, sem gravar: o plano vem com id nulo (ainda não existe). Sem plano, problema diz
 * por quê (ex.: estoque insuficiente).
 */
public record PlanoPrevioResponse(UUID medicamentoId, OtimizacaoResponse plano, String problema) {

    public static PlanoPrevioResponse fromPlanoPrevio(PlanoPrevio p) {
        return new PlanoPrevioResponse(p.medicamentoId(),
                p.plano() == null ? null : OtimizacaoResponse.fromOtimizacao(p.plano()), p.problema());
    }
}
