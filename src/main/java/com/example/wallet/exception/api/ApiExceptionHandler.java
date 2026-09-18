package com.example.wallet.exception.api;

import com.example.wallet.exception.domain.CorruptHistoryException;
import com.example.wallet.exception.domain.WalletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Единый ProblemDetail с устойчивым code. Внутренние ошибки логируются полностью,
 * но SQL, stack trace и содержимое неизвестного payload не отправляются клиенту.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Сопоставляет устойчивый бизнес-код HTTP-статусу без изменения его семантики. */
    @ExceptionHandler(WalletException.class)
    public ResponseEntity<ProblemDetail> wallet(WalletException error, HttpServletRequest request) {
        HttpStatus status = switch (error.code()) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case WALLET_NOT_FOUND, VERSION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.CONFLICT;
        };
        return problem(status, error.code().name(), error.getMessage(), request);
    }

    /** Логирует нарушение истории один раз и возвращает безопасное описание ошибки 500. */
    @ExceptionHandler(CorruptHistoryException.class)
    public ResponseEntity<ProblemDetail> corrupt(CorruptHistoryException error, HttpServletRequest request) {
        log.error("Нарушение целостности истории или receipt", error);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "CORRUPT_HISTORY", "Ошибка целостности сохранённых данных", request);
    }

    /** Преобразует ошибки чтения тела и параметров в прежний INVALID_REQUEST. */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ProblemDetail> invalid(Exception error, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Некорректный JSON или параметр запроса", request);
    }

    /** Сохраняет клиентские статусы MVC; неожиданные ошибки скрывает за INTERNAL_ERROR. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception error, HttpServletRequest request) {
        // Ошибки MVC включают некорректный JSON, UUID, отсутствующий заголовок и Bean Validation.
        // Используем только статус ErrorResponse; его detail может содержать внутренние сведения.
        if (error instanceof ErrorResponse response && response.getStatusCode().is4xxClientError()) {
            int status = response.getStatusCode().value();
            String code = switch (status) {
                case 400 -> "INVALID_REQUEST";
                case 404 -> "RESOURCE_NOT_FOUND";
                case 405 -> "METHOD_NOT_ALLOWED";
                case 406 -> "NOT_ACCEPTABLE";
                case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                default -> "HTTP_ERROR";
            };
            return problem(response.getStatusCode(), code, "Некорректный HTTP-запрос", request);
        }
        if (error instanceof ConstraintViolationException) {
            return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Нарушены ограничения входных данных", request);
        }
        log.error("Неожиданная ошибка обработки запроса", error);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Внутренняя ошибка сервера", request);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatusCode status, String code, String detail,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:wallet:error:" + code));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
