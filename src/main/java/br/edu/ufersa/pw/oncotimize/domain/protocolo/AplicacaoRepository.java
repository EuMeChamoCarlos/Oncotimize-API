package br.edu.ufersa.pw.oncotimize.domain.protocolo;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AplicacaoRepository extends JpaRepository<Aplicacao, UUID> {

    /** Agenda do dia: aplicações pela data agendada (já reflete remarcações aceitas). */
    Page<Aplicacao> findByDataAgendada(LocalDate dataAgendada, Pageable pageable);
}
