package br.edu.ufersa.pw.oncotimize.application.usecases.otimizacao;

import br.edu.ufersa.pw.oncotimize.domain.exception.EstoqueInsuficienteException;
import br.edu.ufersa.pw.oncotimize.domain.exception.RecursoDuplicadoException;
import br.edu.ufersa.pw.oncotimize.domain.exception.RecursoNaoEncontradoException;
import br.edu.ufersa.pw.oncotimize.domain.frascos.Frascos;
import br.edu.ufersa.pw.oncotimize.domain.frascos.FrascosRepository;
import br.edu.ufersa.pw.oncotimize.domain.medicamento.Medicamento;
import br.edu.ufersa.pw.oncotimize.domain.medicamento.MedicamentoRepository;
import br.edu.ufersa.pw.oncotimize.domain.otimizacao.Otimizacao;
import br.edu.ufersa.pw.oncotimize.domain.otimizacao.OtimizacaoRepository;
import br.edu.ufersa.pw.oncotimize.domain.otimizacao.StatusOtimizacao;
import br.edu.ufersa.pw.oncotimize.domain.prescricao.Prescricao;
import br.edu.ufersa.pw.oncotimize.domain.prescricao.PrescricaoRepository;
import br.edu.ufersa.pw.oncotimize.domain.prescricao.StatusPrescricao;
import br.edu.ufersa.pw.oncotimize.domain.services.SolverFracionamento;
import br.edu.ufersa.pw.oncotimize.domain.services.SolverFracionamento.Alocacao;
import br.edu.ufersa.pw.oncotimize.domain.services.SolverFracionamento.Solucao;
import br.edu.ufersa.pw.oncotimize.domain.services.SolverFracionamento.TipoFrasco;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Caso de uso: otimizar o fracionamento de um medicamento num dia (instância do artigo).
 * Pacientes = prescrições APTO do dia; tipos = apresentações do medicamento.
 */
@Service
public class OtimizarDiaUseCase {

    private final MedicamentoRepository medicamentoRepository;
    private final PrescricaoRepository prescricaoRepository;
    private final FrascosRepository frascosRepository;
    private final OtimizacaoRepository otimizacaoRepository;

    public OtimizarDiaUseCase(
            MedicamentoRepository medicamentoRepository,
            PrescricaoRepository prescricaoRepository,
            FrascosRepository frascosRepository,
            OtimizacaoRepository otimizacaoRepository) {
        this.medicamentoRepository = medicamentoRepository;
        this.prescricaoRepository = prescricaoRepository;
        this.frascosRepository = frascosRepository;
        this.otimizacaoRepository = otimizacaoRepository;
    }

    @Transactional
    public Otimizacao executar(UUID medicamentoId, LocalDate data) {
        // Preparo do dia já confirmado: um plano novo nunca poderia ser confirmado (ConfirmarOtimizacaoUseCase
        // recusa o segundo com o mesmo 409). Falha aqui, antes de rodar o solver.
        if (otimizacaoRepository.existsByMedicamentoIdAndDataReferenciaAndStatus(
                medicamentoId, data, StatusOtimizacao.CONFIRMADA)) {
            throw new RecursoDuplicadoException("Plano confirmado", "medicamento e data", data.toString());
        }
        return otimizacaoRepository.save(montar(medicamentoId, data));
    }

    /**
     * Calcula o plano sem gravar (a prévia do painel usa o mesmo cálculo, então os números batem com o plano gerado).
     * Quem chama precisa de uma transação: a entidade aponta para prescrições e frascos carregados.
     */
    // ponytail: agrupa por dia inteiro (como o artigo); janela de estabilidade (~2h) ou lote escolhido entram aqui
    public Otimizacao montar(UUID medicamentoId, LocalDate data) {
        Medicamento medicamento = medicamentoRepository.findById(medicamentoId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Medicamento", medicamentoId));

        List<Prescricao> prescricoes = prescricaoRepository.buscarDoDia(medicamentoId, data, StatusPrescricao.APTO);
        if (prescricoes.isEmpty()) {
            throw new RecursoNaoEncontradoException("Prescrição APTO", "medicamento=" + medicamentoId + " data=" + data);
        }
        List<Frascos> frascos = frascosRepository.findByMedicamentoId(medicamentoId);

        List<Double> doses = prescricoes.stream().map(Prescricao::getDoseMg).toList();
        List<TipoFrasco> tipos = frascos.stream()
                .map(f -> new TipoFrasco(f.getVolumeMg(), f.getCusto(), f.getQuantidadeEstoque()))
                .toList();

        // Pré-condição da patente BR102024012896 [0029]: estoque total cobre a demanda do dia.
        double demanda = doses.stream().mapToDouble(Double::doubleValue).sum();
        double capacidade = tipos.stream().mapToDouble(t -> t.volumeMg() * t.estoque()).sum();
        if (capacidade + 1e-6 < demanda) {
            throw new EstoqueInsuficienteException(medicamentoId, demanda, capacidade);
        }

        Solucao solucao = SolverFracionamento.resolver(doses, tipos);
        BigDecimal custoOtimizado = dinheiro(solucao.custoTotal());
        BigDecimal custoSemCompartilhar = dinheiro(SolverFracionamento.custoSemCompartilhamento(doses, tipos));

        Otimizacao otimizacao = new Otimizacao(medicamento, data, custoOtimizado, solucao.desperdicioMg(),
                custoSemCompartilhar.subtract(custoOtimizado));
        for (Alocacao a : solucao.alocacoes()) {
            otimizacao.adicionarAlocacao(prescricoes.get(a.paciente()), frascos.get(a.tipo()),
                    a.numero() + 1, a.fracao());
        }
        return otimizacao;
    }

    private static BigDecimal dinheiro(double valor) {
        return BigDecimal.valueOf(valor).setScale(4, RoundingMode.HALF_UP);
    }
}
