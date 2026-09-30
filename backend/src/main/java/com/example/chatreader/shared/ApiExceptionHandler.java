package com.example.chatreader.shared;

import com.example.chatreader.importer.domain.ImportException;
import com.example.chatreader.security.AuthController;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.net.URI;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Tratamento global de erros. Nunca loga conteudo de mensagem nem token: apenas
 * classe, mensagem e caminho (secao 31).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException e, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Recurso nao encontrado", e.getMessage(), request);
    }

    @ExceptionHandler(ImportException.class)
    public ProblemDetail handleImport(ImportException e, HttpServletRequest request) {
        log.warn("Importacao rejeitada em {}: {}", request.getRequestURI(), e.getMessage());
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Arquivo de importacao invalido",
                e.getMessage(), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParameter(MissingServletRequestParameterException e,
                                                HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Parametro ausente",
                "O parametro '" + e.getParameterName() + "' e obrigatorio", request);
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException e, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Conflito", e.getMessage(), request);
    }

    @ExceptionHandler(AuthController.BadCredentialsException.class)
    public ProblemDetail handleBadCredentials(AuthController.BadCredentialsException e,
                                              HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "Credenciais invalidas", e.getMessage(), request);
    }

    @ExceptionHandler({IllegalArgumentException.class})
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Requisicao invalida", e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        var details = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "Validacao falhou", details, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleMethodValidation(HandlerMethodValidationException e,
                                                HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Requisicao invalida",
                "Um ou mais parametros estao fora dos limites permitidos.", request);
    }

    /**
     * Valor de parametro que nao converte para o tipo esperado (ex.: {@code format=PDF}).
     * A especificacao trata formato de importacao desconhecido como 422 (secao 14).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                            HttpServletRequest request) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Parametro invalido",
                "Valor invalido para '" + e.getName() + "': " + e.getValue(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleMaxUpload(MaxUploadSizeExceededException e, HttpServletRequest request) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "Arquivo grande demais",
                "O arquivo excede o limite configurado em chatreader.import.max-bytes", request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Erro inesperado em {}: {}", request.getRequestURI(), e.getClass().getName(), e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno",
                "Ocorreu um erro inesperado. Consulte os logs do servidor.", request);
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail == null ? title : detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}
