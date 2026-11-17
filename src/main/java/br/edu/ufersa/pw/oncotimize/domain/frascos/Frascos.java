package br.edu.ufersa.pw.oncotimize.domain.frascos;

import br.edu.ufersa.pw.oncotimize.domain.exception.FrascoInvalidoException;
import br.edu.ufersa.pw.oncotimize.domain.medicamento.Medicamento;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;
import java.util.UUID;

/**
 * Apresentação de frasco de um medicamento. A baixa de estoque na confirmação do plano é um UPDATE
 * atômico (FrascosRepository.baixarEstoque), não um método daqui: evita corrida entre confirmações.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "apresentacao_frasco")
public class Frascos {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "medicamento_id", nullable = false)
    private Medicamento medicamento;

    @Column(nullable = false)
    private Double volumeMg; // Representa v_j (volume do frasco tipo j)

    @Column(nullable = false)
    private Double custo; // Representa c_j (custo do frasco tipo j)

    @Column(name = "quantidade_estoque", nullable = false)
    private Integer quantidadeEstoque; // Representa e_j (estoque disponível)

    @Column(name = "descricao_tasy")
    private String descricaoTasy; // Nome da apresentação no Tasy; opcional

    public Frascos(Medicamento medicamento, Double volumeMg, Double custo, Integer quantidadeEstoque,
                   String descricaoTasy) {
        this.medicamento = Objects.requireNonNull(medicamento, "medicamento");
        redefinirApresentacao(volumeMg, custo, descricaoTasy);
        ajustarEstoque(quantidadeEstoque);
    }

    /** Volume e custo entram no modelo; quem chama garante que não mudam se o frasco já foi usado num plano. */
    public void redefinirApresentacao(Double volumeMg, Double custo, String descricaoTasy) {
        if (volumeMg == null || volumeMg <= 0) {
            throw new FrascoInvalidoException("volumeMg", "deve ser maior que zero");
        }
        if (custo == null || custo <= 0) {
            throw new FrascoInvalidoException("custo", "deve ser maior que zero");
        }
        this.volumeMg = volumeMg;
        this.custo = custo;
        this.descricaoTasy = descricaoTasy;
    }

    /** Ajuste manual do estoque (inventário); a baixa pela confirmação é atômica no repositório. */
    public void ajustarEstoque(Integer quantidade) {
        if (quantidade == null || quantidade < 0) {
            throw new FrascoInvalidoException("quantidadeEstoque", "não pode ser negativa");
        }
        this.quantidadeEstoque = quantidade;
    }
}
