package br.edu.ufersa.pw.oncotimize.application.usecases.agenda;

import br.edu.ufersa.pw.oncotimize.application.usecases.agenda.PreverSugestoesUseCase.Cenario;
import br.edu.ufersa.pw.oncotimize.domain.agenda.SugestaoRemarcacao;
import br.edu.ufersa.pw.oncotimize.domain.agenda.SugestaoRemarcacaoRepository;
import br.edu.ufersa.pw.oncotimize.domain.exception.EstadoInvalidoException;
import br.edu.ufersa.pw.oncotimize.domain.exception.PeriodoInvalidoException;
import br.edu.ufersa.pw.oncotimize.domain.exception.RecursoNaoEncontradoException;
import br.edu.ufersa.pw.oncotimize.domain.exception.SemDiaDeRetornoException;
import br.edu.ufersa.pw.oncotimize.domain.otimizacao.AlocacaoFrascoRepository;
import br.edu.ufersa.pw.oncotimize.domain.otimizacao.StatusOtimizacao;
import br.edu.ufersa.pw.oncotimize.domain.prescricao.Prescricao;
import br.edu.ufersa.pw.oncotimize.domain.prescricao.PrescricaoRepository;
import br.edu.ufersa.pw.oncotimize.domain.prescricao.StatusPrescricao;
import br.edu.ufersa.pw.oncotimize.domain.protocolo.Aplicacao;
import br.edu.ufersa.pw.oncotimize.domain.protocolo.AplicacaoRepository;
import br.edu.ufersa.pw.oncotimize.domain.protocolo.ProtocoloMedicamento;
import br.edu.ufersa.pw.oncotimize.domain.protocolo.StatusAplicacao;
import br.edu.ufersa.pw.oncotimize.domain.services.PlanejadorAgenda;
import br.edu.ufersa.pw.oncotimize.domain.services.PlanejadorAgenda.AplicacaoPlanejada;
import br.edu.ufersa.pw.oncotimize.domain.services.PlanejadorAgenda.Sugestao;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Caso de uso: paciente não apto precisa voltar a partir de uma data mínima (definida pelo médico). Entre a
 * data mínima e alguns dias depois, sugere o dia em que a aplicação inteira custa menos somada aos outros
 * pacientes dos mesmos medicamentos, e grava como sugestão NAO_APTO para a enfermagem aceitar ou recusar.
 * Respeita o intervalo mínimo com as vizinhas; as aplicações seguintes não se movem (a definir com a LMECC).
 * Na mesma transação, as linhas APTO da aplicação viram NAO_APTO no dia da avaliação: a otimização do dia deixa de
 * contar o paciente, aceite ou não a sugestão.
 */
@Service
public class SugerirRemarcacaoNaoAptoUseCase {

    private final AplicacaoRepository aplicacaoRepository;
    private final SugestaoRemarcacaoRepository sugestaoRepository;
    private final PrescricaoRepository prescricaoRepository;
    private final AlocacaoFrascoRepository alocacaoFrascoRepository;
    private final PreverSugestoesUseCase preverSugestoes;
    private final int janelaDias;

    public SugerirRemarcacaoNaoAptoUseCase(
            AplicacaoRepository aplicacaoRepository,
            SugestaoRemarcacaoRepository sugestaoRepository,
            PrescricaoRepository prescricaoRepository,
            AlocacaoFrascoRepository alocacaoFrascoRepository,
            PreverSugestoesUseCase preverSugestoes,
            // ponytail: prazo de busca fixo (14 dias) até a LMECC informar o prazo real por causa
            @Value("${oncotimize.agenda.janela-nao-apto-dias:14}") int janelaDias) {
        this.aplicacaoRepository = aplicacaoRepository;
        this.sugestaoRepository = sugestaoRepository;
        this.prescricaoRepository = prescricaoRepository;
        this.alocacaoFrascoRepository = alocacaoFrascoRepository;
        this.preverSugestoes = preverSugestoes;
        this.janelaDias = janelaDias;
    }

    // Sem dia de retorno, o 422 sai mas o não apto continua gravado (é decisão médica, não depende da agenda).
    @Transactional(noRollbackFor = SemDiaDeRetornoException.class)
    public SugestaoRemarcacao executar(UUID aplicacaoId, LocalDate dataMinima, String motivo) {
        Aplicacao aplicacao = aplicacaoRepository.findById(aplicacaoId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Aplicação", aplicacaoId));
        if (aplicacao.getStatus() != StatusAplicacao.AGENDADA) {
            throw new EstadoInvalidoException("Aplicação", aplicacaoId, "já está " + aplicacao.getStatus());
        }
        if (!dataMinima.isAfter(aplicacao.getDataAgendada())) {
            throw new PeriodoInvalidoException(dataMinima, dataMinima,
                    "a data mínima deve ser depois da data agendada (" + aplicacao.getDataAgendada() + ")");
        }
        // Linhas APTO da aplicação = as que a otimização do dia agendado usa. Frasco só se abre para paciente apto:
        // se algum medicamento do dia (ex: um do AC) já teve o preparo confirmado, o não apto não cabe mais.
        List<Prescricao> aptas = prescricaoRepository.findByAplicacaoId(aplicacaoId).stream()
                .filter(p -> p.getStatus() == StatusPrescricao.APTO)
                .toList();
        if (aptas.stream().anyMatch(p ->
                alocacaoFrascoRepository.existsByPrescricaoIdAndOtimizacaoStatus(p.getId(), StatusOtimizacao.CONFIRMADA))) {
            throw new EstadoInvalidoException("Aplicação", aplicacaoId, "já tem preparo confirmado em "
                    + aplicacao.getDataAgendada() + "; o frasco só se abre para paciente apto");
        }
        LocalDate ate = dataMinima.plusDays(janelaDias);

        // A aplicação vai inteira: dose de cada quimioterápico IV do dia, como no protocolo.
        Map<UUID, Double> doses = new HashMap<>();
        for (ProtocoloMedicamento item : aplicacao.medicamentosDoDia()) {
            if (PreverSugestoesUseCase.intravenoso(item)) {
                doses.put(item.getMedicamento().getId(), item.getDoseMg());
            }
        }
        if (doses.isEmpty()) {
            throw new EstadoInvalidoException("Aplicação", aplicacaoId, "não tem quimioterápico intravenoso no dia");
        }

        // Validações feitas: o não apto vale daqui em diante, ache-se ou não um dia de retorno.
        aptas.forEach(Prescricao::marcarNaoApto);

        Cenario cenario = preverSugestoes.montarCenario(Set.copyOf(doses.keySet()), dataMinima, ate);
        List<AplicacaoPlanejada> outras = cenario.aplicacoes().stream()
                .filter(a -> !a.id().equals(aplicacaoId))
                .collect(Collectors.toList());
        // Vizinhas do mesmo protocolo fora do período também contam para o intervalo mínimo.
        aplicacao.getProtocolo().getAplicacoes().stream()
                .filter(v -> !v.getId().equals(aplicacaoId) && outras.stream().noneMatch(o -> o.id().equals(v.getId())))
                .forEach(v -> outras.add(new AplicacaoPlanejada(v.getId(), v.getProtocolo().getId(),
                        v.getProtocolo().getCodigoProtocolo(), v.getNumero(), Map.of(), v.getDataAgendada(),
                        v.getDataAgendada(), v.getDataAgendada(), v.getProtocolo().getIntervaloMinimoDias(), false)));
        AplicacaoPlanejada alvo = new AplicacaoPlanejada(aplicacaoId, aplicacao.getProtocolo().getId(),
                aplicacao.getProtocolo().getCodigoProtocolo(), aplicacao.getNumero(), doses, aplicacao.getDataAgendada(),
                dataMinima, ate, aplicacao.getProtocolo().getIntervaloMinimoDias(), true);

        Sugestao melhor = PlanejadorAgenda.melhorDia(alvo, outras, cenario.dosesFixas(), cenario.tipos(),
                        dataMinima, ate, LocalDate.now(), cenario.diasFechados())
                .orElseThrow(() -> new SemDiaDeRetornoException(aplicacaoId,
                        "nenhum dia possível entre " + dataMinima + " e " + ate
                                + " (intervalo mínimo com as vizinhas ou estoque); o paciente ficou registrado como não apto"));

        sugestaoRepository.expirarPendentes(aplicacaoId, null);
        return sugestaoRepository.save(SugestaoRemarcacao.deNaoApto(aplicacao, melhor.destino(),
                BigDecimal.valueOf(melhor.ganho()).setScale(4, RoundingMode.HALF_UP), motivo));
    }
}
