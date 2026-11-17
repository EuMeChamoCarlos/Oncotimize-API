package br.edu.ufersa.pw.oncotimize.domain.exception;

/**
 * Nenhum dia possível para o retorno do paciente não apto (→ 422, como toda {@link EstadoInvalidoException}).
 * Tipo próprio para a transação não ser desfeita: o registro do não apto vale mesmo sem dia sugerido.
 */
public class SemDiaDeRetornoException extends EstadoInvalidoException {

    public SemDiaDeRetornoException(Object aplicacaoId, String motivo) {
        super("Aplicação", aplicacaoId, motivo);
    }
}
