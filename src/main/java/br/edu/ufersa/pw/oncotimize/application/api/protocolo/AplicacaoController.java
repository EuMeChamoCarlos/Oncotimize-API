package br.edu.ufersa.pw.oncotimize.application.api.protocolo;

import br.edu.ufersa.pw.oncotimize.application.api.protocolo.dto.AplicacaoDoDiaResponse;
import br.edu.ufersa.pw.oncotimize.application.api.protocolo.dto.AplicacaoResponse;
import br.edu.ufersa.pw.oncotimize.application.usecases.protocolo.ListarAplicacoesDoDiaUseCase;
import java.time.LocalDate;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.format.annotation.DateTimeFormat;
import br.edu.ufersa.pw.oncotimize.application.api.protocolo.dto.JanelaRequest;
import br.edu.ufersa.pw.oncotimize.application.usecases.protocolo.DefinirJanelaAplicacaoUseCase;
import br.edu.ufersa.pw.oncotimize.application.api.agenda.dto.SugestaoDtos.RemarcacaoRequest;
import br.edu.ufersa.pw.oncotimize.application.api.agenda.dto.SugestaoDtos.SugestaoResponse;
import br.edu.ufersa.pw.oncotimize.application.usecases.agenda.SugerirRemarcacaoNaoAptoUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/aplicacoes")
@RequiredArgsConstructor
public class AplicacaoController {
    private final DefinirJanelaAplicacaoUseCase definirJanelaAplicacaoUseCase;
    private final SugerirRemarcacaoNaoAptoUseCase sugerirRemarcacaoNaoAptoUseCase;
    private final ListarAplicacoesDoDiaUseCase listarAplicacoesDoDiaUseCase;

    /** Agenda do dia: aplicações pela data agendada (inclui as que chegaram por remarcação). */
    @GetMapping
    public ResponseEntity<PagedModel<AplicacaoDoDiaResponse>> doDia(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @PageableDefault(size = 200, sort = "protocolo.codigoProtocolo") Pageable pageable) {
        return ResponseEntity.ok(new PagedModel<>(
                listarAplicacoesDoDiaUseCase.executar(data, pageable).map(AplicacaoDoDiaResponse::fromAplicacao)));
    }

    /**
     * Paciente não apto: sugere o melhor dia a partir da data mínima e grava como sugestão NAO_APTO
     * (aceitar/recusar em /api/agenda/sugestoes/{id}/aceite|recusa).
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{aplicacaoId}/remarcacao")
    public ResponseEntity<SugestaoResponse> remarcar(@PathVariable UUID aplicacaoId,
                                                     @Valid @RequestBody RemarcacaoRequest request) {
        return ResponseEntity.status(201).body(SugestaoResponse.fromSugestao(
                sugerirRemarcacaoNaoAptoUseCase.executar(aplicacaoId, request.dataMinima(), request.motivo())));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{aplicacaoId}/janela")
    public ResponseEntity<AplicacaoResponse> definirJanela(
            @PathVariable UUID aplicacaoId,
            @Valid @RequestBody JanelaRequest request) {
        return ResponseEntity.ok(AplicacaoResponse.fromAplicacao(
                definirJanelaAplicacaoUseCase.executar(aplicacaoId, request)));
    }
}
