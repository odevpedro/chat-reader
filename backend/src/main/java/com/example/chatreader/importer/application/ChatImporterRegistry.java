package com.example.chatreader.importer.application;

import com.example.chatreader.importer.domain.ChatImporter;
import com.example.chatreader.importer.domain.ImportException;
import com.example.chatreader.importer.domain.ImportSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Resolve qual {@link ChatImporter} processa uma {@link ImportSource}.
 *
 * <p>Hierarquia explicavel (ADR-004):
 * <ol>
 *   <li>se o cliente declarou {@code declaredFormat}, usa o adapter correspondente
 *       (falha com mensagem clara se nao existir);
 *   <li>senao, tenta cada adapter por {@link ChatImporter#supports};
 *   <li>se nenhum reconhecer, lanca {@link ImportException} com os formatos suportados.
 * </ol>
 */
@Component
public class ChatImporterRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChatImporterRegistry.class);

    private final List<ChatImporter> importers;

    public ChatImporterRegistry(List<ChatImporter> importers) {
        this.importers = List.copyOf(importers);
    }

    public ChatImporter resolve(ImportSource source) {
        if (source.declaredFormat() != null) {
            return byFormat(source.declaredFormat().name())
                    .orElseThrow(() -> new ImportException(
                            "Nao ha adapter para o formato declarado: " + source.declaredFormat()
                                    + ". Formatos suportados: " + supportedFormats()));
        }

        return importers.stream()
                .filter(importer -> importer.supports(source))
                .findFirst()
                .orElseThrow(() -> new ImportException(
                        "Nao foi possivel reconhecer o formato de "
                                + (source.filename() == null ? "arquivo" : "'" + source.filename() + "'")
                                + ". Formatos suportados: " + supportedFormats()
                                + ". Informe ?format=JSON|MARKDOWN|CHATGPT_EXPORT para forcar."));
    }

    public Optional<ChatImporter> byFormat(String format) {
        return importers.stream()
                .filter(importer -> importer.format().name().equalsIgnoreCase(format))
                .findFirst();
    }

    public List<String> supportedFormats() {
        return importers.stream().map(i -> i.format().name()).sorted().toList();
    }
}
