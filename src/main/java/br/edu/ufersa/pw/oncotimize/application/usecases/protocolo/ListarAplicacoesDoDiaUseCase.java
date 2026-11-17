package br.edu.ufersa.pw.oncotimize.application.usecases.protocolo;

import br.edu.ufersa.pw.oncotimize.domain.protocolo.Aplicacao;
import br.edu.ufersa.pw.oncotimize.domain.protocolo.AplicacaoRepository;
import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Caso de uso: agenda do dia (somente leitura). Lista as aplicações pela data agendada, então uma remarcação aceita
 * aparece no dia novo mesmo antes de existir a prescrição desse dia.
 */
@Service
public class ListarAplicacoesDoDiaUseCase {

    private final AplicacaoRepository aplicacaoRepository;

    public ListarAplicacoesDoDiaUseCase(AplicacaoRepository aplicacaoRepository) {
        this.aplicacaoRepository = aplicacaoRepository;
    }

    @Transactional(readOnly = true)
    public Page<Aplicacao> executar(LocalDate data, Pageable pageable) {
        return aplicacaoRepository.findByDataAgendada(data, pageable);
    }
}
