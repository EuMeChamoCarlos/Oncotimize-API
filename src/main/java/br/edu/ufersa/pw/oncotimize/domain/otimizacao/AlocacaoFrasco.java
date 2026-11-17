package br.edu.ufersa.pw.oncotimize.domain.otimizacao;

import br.edu.ufersa.pw.oncotimize.domain.frascos.Frascos;
import br.edu.ufersa.pw.oncotimize.domain.prescricao.Prescricao;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;
import java.util.UUID;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "alocacao_frasco",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_alocacao",
                columnNames = {"otimizacao_id", "prescricao_id", "apresentacao_id", "numero_frasco"}))
public class AlocacaoFrasco {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "otimizacao_id", nullable = false)
    private Otimizacao otimizacao;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prescricao_id", nullable = false)
    private Prescricao prescricao;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "apresentacao_id", nullable = false)
    private Frascos apresentacao;

    @Column(name = "numero_frasco", nullable = false)
    private Integer numeroFrasco;

    @Column(nullable = false)
    private Double fracao;

    /** Só a Otimizacao cria alocações (Otimizacao.adicionarAlocacao). Valores do solver: inválido = bug. */
    AlocacaoFrasco(Otimizacao otimizacao, Prescricao prescricao, Frascos apresentacao, int numeroFrasco, double fracao) {
        if (numeroFrasco < 1) {
            throw new IllegalArgumentException("numeroFrasco deve ser >= 1: " + numeroFrasco);
        }
        if (!(fracao > 0 && fracao <= 1)) {
            throw new IllegalArgumentException("fracao deve estar em (0, 1]: " + fracao);
        }
        this.otimizacao = Objects.requireNonNull(otimizacao, "otimizacao");
        this.prescricao = Objects.requireNonNull(prescricao, "prescricao");
        this.apresentacao = Objects.requireNonNull(apresentacao, "apresentacao");
        this.numeroFrasco = numeroFrasco;
        this.fracao = fracao;
    }

    @Transient
    public Double getVolumeUtilizadoMg() {
        return fracao * apresentacao.getVolumeMg();
    }
}
