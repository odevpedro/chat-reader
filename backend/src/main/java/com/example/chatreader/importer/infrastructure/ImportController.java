package com.example.chatreader.importer.infrastructure;

import com.example.chatreader.importer.application.ChatImporterRegistry;
import com.example.chatreader.importer.application.ImportService;
import com.example.chatreader.importer.domain.ImportFormat;
import com.example.chatreader.importer.domain.ImportSource;
import com.example.chatreader.metrics.ChatReaderMetrics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/chats/import")
@Tag(name = "Import", description = "Importacao de conversas de arquivos exportados")
public class ImportController {

    private final ImportService importService;
    private final ChatImporterRegistry registry;
    private final ChatReaderMetrics metrics;

    public ImportController(ImportService importService, ChatImporterRegistry registry,
                            ChatReaderMetrics metrics) {
        this.importService = importService;
        this.registry = registry;
        this.metrics = metrics;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Importa conversas de um arquivo (multipart/form-data)")
    public ImportResponse importFile(
            @Parameter(description = "Arquivo exportado (.json, .md)")
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "Formato. Opcional: se ausente, detectado por assinatura.")
            @RequestParam(value = "format", required = false) ImportFormat format) {

        if (file.isEmpty()) {
            throw new IllegalArgumentException("Arquivo vazio");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Nao foi possivel ler o arquivo enviado", e);
        }
        return run(new ImportSource(content, format, file.getOriginalFilename()));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Importa conversas de um JSON no corpo da requisicao (payload menor)")
    public ImportResponse importJson(@RequestParam(value = "format", required = false) ImportFormat format,
                                     @RequestBody @NotNull ImportJsonRequest body) {
        return run(new ImportSource(body.content(), format, body.filename()));
    }

    @GetMapping("/formats")
    @Operation(summary = "Lista os formatos de importacao suportados")
    public FormatsResponse formats() {
        return new FormatsResponse(registry.supportedFormats());
    }

    private ImportResponse run(ImportSource source) {
        var startedAt = System.nanoTime();
        var result = importService.importSource(source);
        metrics.recordImport(result.created(), result.updated(), result.skipped(), result.failed());
        metrics.recordImportDuration(java.time.Duration.ofNanos(System.nanoTime() - startedAt));
        return new ImportResponse(result.total(), result.created(), result.updated(),
                result.skipped(), result.failed(), result.failures());
    }

    public record ImportJsonRequest(String content, String filename) {
    }

    public record ImportResponse(
            int total, int created, int updated, int skipped, int failed, List<String> failures
    ) {
    }

    public record FormatsResponse(List<String> formats) {
    }
}
