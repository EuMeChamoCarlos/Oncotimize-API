package br.edu.ufersa.pw.oncotimize.application.api.otimizacao;

import br.edu.ufersa.pw.oncotimize.application.api.otimizacao.dto.OtimizacaoResponse;
import br.edu.ufersa.pw.oncotimize.application.api.otimizacao.dto.OtimizarRequest;
import br.edu.ufersa.pw.oncotimize.application.api.otimizacao.dto.PlanoPrevioResponse;
import br.edu.ufersa.pw.oncotimize.application.usecases.otimizacao.PreverPlanoDoDiaUseCase;
import java.util.List;
import br.edu.ufersa.pw.oncotimize.application.usecases.otimizacao.BuscarOtimizacaoUseCase;
import br.edu.ufersa.pw.oncotimize.application.usecases.otimizacao.ConfirmarOtimizacaoUseCase;
import br.edu.ufersa.pw.oncotimize.application.usecases.otimizacao.OtimizarDiaUseCase;
import br.edu.ufersa.pw.oncotimize.application.usecases.otimizacao.RecusarOtimizacaoUseCase;
import br.edu.ufersa.pw.oncotimize.application.api.agenda.dto.SugestaoDtos.DecisaoRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import br.edu.ufersa.pw.oncotimize.domain.otimizacao.Otimizacao;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/otimizacoes")
@RequiredArgsConstructor
public class OtimizacaoController {
    private final OtimizarDiaUseCase otimizarDiaUseCase;
    private final BuscarOtimizacaoUseCase buscarOtimizacaoUseCase;
    private final ConfirmarOtimizacaoUseCase confirmarOtimizacaoUseCase;
    private final RecusarOtimizacaoUseCase recusarOtimizacaoUseCase;
    private final PreverPlanoDoDiaUseCase preverPlanoDoDiaUseCase;

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<OtimizacaoResponse> otimizar(@Valid @RequestBody OtimizarRequest request) {
        Otimizacao criada = otimizarDiaUseCase.executar(request.medicamentoId(), request.data());
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(criada.getId())
                .toUri();
        return ResponseEntity.created(location).body(OtimizacaoResponse.fromOtimizacao(criada));
    }

    /** Planos do dia (de um medicamento ou de todos), o mais recente primeiro; lista vazia se ainda não houve otimização. */
    @GetMapping
    public ResponseEntity<PagedModel<OtimizacaoResponse>> listar(
            @RequestParam(required = false) UUID medicamentoId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(new PagedModel<>(buscarOtimizacaoUseCase.listar(medicamentoId, data, pageable)
                .map(OtimizacaoResponse::fromOtimizacao)));
    }

    /** Só leitura: o plano de cada medicamento com paciente apto no dia, calculado na hora e sem gravar. */
    @GetMapping("/previa")
    public ResponseEntity<List<PlanoPrevioResponse>> previa(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data) {
        return ResponseEntity.ok(preverPlanoDoDiaUseCase.executar(data).stream()
                .map(PlanoPrevioResponse::fromPlanoPrevio).toList());
    }

    @GetMapping("/{otimizacaoId}")
    public ResponseEntity<OtimizacaoResponse> getById(@PathVariable UUID otimizacaoId) {
        return ResponseEntity.ok(OtimizacaoResponse.fromOtimizacao(buscarOtimizacaoUseCase.porId(otimizacaoId)));
    }

    /** PUT idempotente: repetir (ex: resposta perdida na rede) devolve o mesmo estado, sem baixar de novo. */
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{otimizacaoId}/confirmacao")
    public ResponseEntity<OtimizacaoResponse> confirmar(@PathVariable UUID otimizacaoId) {
        return ResponseEntity.ok(OtimizacaoResponse.fromOtimizacao(confirmarOtimizacaoUseCase.executar(otimizacaoId)));
    }

    /** O profissional recusa o plano (motivo opcional). Plano confirmado não se recusa → 422. */
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{otimizacaoId}/recusa")
    public ResponseEntity<OtimizacaoResponse> recusar(@PathVariable UUID otimizacaoId,
                                                      @Valid @RequestBody(required = false) DecisaoRequest request,
                                                      @AuthenticationPrincipal UserDetails usuario) {
        String motivo = request == null ? null : request.motivo();
        return ResponseEntity.ok(OtimizacaoResponse.fromOtimizacao(
                recusarOtimizacaoUseCase.executar(otimizacaoId, usuario.getUsername(), motivo)));
    }
}
