package br.edu.ufersa.pw.oncotimize.domain.agenda;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface SugestaoRemarcacaoRepository extends JpaRepository<SugestaoRemarcacao, UUID> {

    List<SugestaoRemarcacao> findByAplicacaoIdAndStatus(UUID aplicacaoId, SugestaoRemarcacao.Status status);

    Page<SugestaoRemarcacao> findByStatus(SugestaoRemarcacao.Status status, Pageable pageable);

    /** Das aplicações pedidas, as que têm sugestão do tipo e situação dados (ex.: retorno de não apto aceito). */
    @Query("""
            select distinct s.aplicacao.id from SugestaoRemarcacao s
            where s.tipo = :tipo and s.status = :status and s.aplicacao.id in :aplicacaoIds""")
    List<UUID> aplicacoesCom(@Param("aplicacaoIds") Collection<UUID> aplicacaoIds,
                             @Param("tipo") SugestaoRemarcacao.Tipo tipo,
                             @Param("status") SugestaoRemarcacao.Status status);

    /** Uma sugestão nova ou aceita torna obsoletas as outras pendentes da mesma aplicação. */
    default void expirarPendentes(UUID aplicacaoId, UUID exceto) {
        findByAplicacaoIdAndStatus(aplicacaoId, SugestaoRemarcacao.Status.SUGERIDA).stream()
                .filter(s -> !s.getId().equals(exceto))
                .forEach(SugestaoRemarcacao::expirar);
    }
}
