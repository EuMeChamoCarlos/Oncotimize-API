package br.edu.ufersa.pw.oncotimize.application.api.protocolo.dto;

import br.edu.ufersa.pw.oncotimize.domain.protocolo.Aplicacao;
import br.edu.ufersa.pw.oncotimize.domain.protocolo.StatusAplicacao;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Linha da agenda do dia: a aplicação com o código do protocolo e os quimioterápicos do dia (dose do protocolo).
 * dataPrevista diferente de dataAgendada = aplicação remarcada (motivoAlteracao diz por quê).
 */
public record AplicacaoDoDiaResponse(UUID id, UUID protocoloId, String codigoProtocolo, Integer numero,
                                     LocalDate dataPrevista, LocalDate dataAgendada, StatusAplicacao status,
                                     String motivoAlteracao, List<MedicamentoDoDia> medicamentos) {

    public record MedicamentoDoDia(UUID medicamentoId, Double doseMg, String via) {}

    public static AplicacaoDoDiaResponse fromAplicacao(Aplicacao aplicacao) {
        return new AplicacaoDoDiaResponse(
                aplicacao.getId(),
                aplicacao.getProtocolo().getId(),
                aplicacao.getProtocolo().getCodigoProtocolo(),
                aplicacao.getNumero(),
                aplicacao.getDataPrevista(),
                aplicacao.getDataAgendada(),
                aplicacao.getStatus(),
                aplicacao.getMotivoAlteracao(),
                aplicacao.medicamentosDoDia().stream()
                        .map(m -> new MedicamentoDoDia(m.getMedicamento().getId(), m.getDoseMg(), m.getVia()))
                        .toList());
    }
}
