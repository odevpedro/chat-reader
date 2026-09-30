package com.example.chatreader.importer.domain;

import java.util.List;

/**
 * Adapter de importacao. Cada implementacao conhece UM formato de origem e devolve
 * {@link NormalizedChat} — nunca entidades de dominio nem JPA.
 *
 * <p>Implementacoes sao <b>puras</b>: recebem bytes, devolvem dados. A gravacao no banco
 * e responsabilidade de {@code ImportService}. Isso torna cada parser testavel sem banco.
 *
 * <p>Ver ADR-004.
 */
public interface ChatImporter {

    /** Formato que este adapter sabe ler. */
    ImportFormat format();

    /**
     * Detecta se este adapter reconhece o conteudo. Usado pelo registry quando o
     * cliente nao declara o formato. Deve ser barato e sem efeitos colaterais.
     */
    boolean supports(ImportSource source);

    /**
     * Converte o conteudo em conversas normalizadas.
     *
     * @throws ImportException se o arquivo inteiro for ilegivel (formato nao reconhecido,
     *                         JSON invalido no nivel raiz, etc.). Itens individuais
     *                         invalidos nao lancam: sao ignorados aqui e contabilizados
     *                         pelo {@code ImportService}.
     */
    List<NormalizedChat> importChats(ImportSource source);
}
