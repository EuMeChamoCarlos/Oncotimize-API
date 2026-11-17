package br.edu.ufersa.pw.oncotimize.domain.exception;

/**
 * Frasco (apresentação) com valor inválido (→ 400). Invariante violada no nascimento ou em comportamento.
 */
public class FrascoInvalidoException extends DomainException {

    public FrascoInvalidoException(String campo, String motivo) {
        super("Frasco inválido! campo=" + campo + " motivo=" + motivo);
    }
}
