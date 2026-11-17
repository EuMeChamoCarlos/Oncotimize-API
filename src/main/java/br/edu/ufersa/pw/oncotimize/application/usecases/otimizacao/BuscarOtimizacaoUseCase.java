package br.edu.ufersa.pw.oncotimize.application.usecases.otimizacao;

import br.edu.ufersa.pw.oncotimize.domain.exception.RecursoNaoEncontradoException;
import br.edu.ufersa.pw.oncotimize.domain.otimizacao.Otimizacao;
import br.edu.ufersa.pw.oncotimize.domain.otimizacao.OtimizacaoRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Caso de uso: consultar otimizações já calculadas (somente leitura).
 */
@Service
public class BuscarOtimizacaoUseCase {

    private final OtimizacaoRepository otimizacaoRepository;

    public BuscarOtimizacaoUseCase(OtimizacaoRepository otimizacaoRepository) {
        this.otimizacaoRepository = otimizacaoRepository;
    }

    @Transactional(readOnly = true)
    public Otimizacao porId(UUID otimizacaoId) {
        return otimizacaoRepository.findById(otimizacaoId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Otimização", otimizacaoId));
    }

    /** Planos de um medicamento num dia (o front abre o plano do dia por aqui). Lista vazia não é erro. */
    @Transactional(readOnly = true)
    public Page<Otimizacao> listar(UUID medicamentoId, LocalDate data, Pageable pageable) {
        return otimizacaoRepository.findByMedicamentoIdAndDataReferencia(medicamentoId, data, pageable);
    }
}
