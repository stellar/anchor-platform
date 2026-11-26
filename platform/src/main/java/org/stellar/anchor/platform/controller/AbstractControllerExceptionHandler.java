package org.stellar.anchor.platform.controller;

import static io.sentry.Sentry.*;
import static org.stellar.anchor.util.Log.*;

import io.sentry.SentryLevel;
import jakarta.persistence.PersistenceException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.NotSupportedException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.stellar.anchor.api.exception.*;
import org.stellar.anchor.api.sep.CustomerInfoNeededResponse;
import org.stellar.anchor.api.sep.SepExceptionResponse;
import org.stellar.anchor.platform.utils.JsonArrayLimitException;
import org.stellar.anchor.platform.utils.JsonStructureLimitException;
import org.stellar.anchor.platform.utils.RequestBodySizeLimitFilter;
import org.stellar.anchor.platform.utils.RpcUtil;

public abstract class AbstractControllerExceptionHandler {
  static final String INTERNAL_ERROR_MESSAGE = "An internal error occurred.";

  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler({SepValidationException.class, BadRequestException.class})
  public SepExceptionResponse handleBadRequest(AnchorException ex) {
    debugF("Bad request: {}", ex.getMessage());
    return new SepExceptionResponse(ex.getMessage());
  }

  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public SepExceptionResponse handleMissingParams(MissingServletRequestParameterException ex) {
    debugF("Missing server request parameters: {}", ex.getMessage());
    String name = ex.getParameterName();
    return new SepExceptionResponse(String.format("The \"%s\" parameter is missing.", name));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<?> handleRandomException(HttpMessageNotReadableException ex) {
    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
      if (cause instanceof RequestBodySizeLimitFilter.RequestBodyTooLargeException) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(new SepExceptionResponse("The request body is too large."));
      }
      if (cause instanceof JsonStructureLimitException) {
        JsonStructureLimitException limit = (JsonStructureLimitException) cause;
        warnLimit(limit.getLimitName(), limit.getLimit());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new SepExceptionResponse("The request body is too complex."));
      }
      if (cause instanceof JsonArrayLimitException) {
        JsonArrayLimitException limit = (JsonArrayLimitException) cause;
        return handleArrayLimit(limit);
      }
    }
    debugF("Spring is unable to read HTTP message: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new SepExceptionResponse("Your request body is wrong in some way."));
  }

  @ResponseStatus(HttpStatus.FORBIDDEN)
  @ExceptionHandler({SepNotAuthorizedException.class, UnauthorizedException.class})
  public SepExceptionResponse handleAuthError(SepException ex) {
    debugF("SEP-10 authorization failure: {}", ex.getMessage());
    captureMessage("SEP-10 authorization failure: " + ex.getMessage(), SentryLevel.DEBUG);
    return new SepExceptionResponse(ex.getMessage());
  }

  @ExceptionHandler(SepCustomerInfoNeededException.class)
  @ResponseStatus(value = HttpStatus.FORBIDDEN)
  public CustomerInfoNeededResponse handle(SepCustomerInfoNeededException ex) {
    debugF("Customer information is needed: {}", ex.getMessage());
    captureMessage("Customer information is needed: " + ex.getMessage(), SentryLevel.DEBUG);
    return new CustomerInfoNeededResponse(ex.getFields());
  }

  @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
  @ExceptionHandler(SepRateLimitExceededException.class)
  public SepExceptionResponse handleRateLimitExceeded(SepRateLimitExceededException ex) {
    debugF("Rate limit exceeded: {}", ex.getMessage());
    return new SepExceptionResponse(ex.getMessage());
  }

  @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
  @ExceptionHandler({SepNotImplementedException.class, NotSupportedException.class})
  public SepExceptionResponse handleNotImplementedError(Exception ex) {
    errorF("Not implemented: {}", ex.getMessage());
    captureException(ex);
    return new SepExceptionResponse(ex.getMessage());
  }

  // HTTP code: 502
  // Received an invalid response from the upstream server.
  @ResponseStatus(HttpStatus.BAD_GATEWAY)
  @ExceptionHandler({ServerErrorException.class})
  public SepExceptionResponse handleServerErrorException(Exception ex) {
    errorF("An upstream server returns a invalid response: {}", ex);
    captureException(ex);
    return new SepExceptionResponse(ex.getMessage());
  }

  @ExceptionHandler({
    SepNotFoundException.class,
    NotFoundException.class,
    NoResourceFoundException.class
  })
  @ResponseStatus(value = HttpStatus.NOT_FOUND)
  SepExceptionResponse handleNotFound(Exception ex) {
    traceF("Not found: {}", ex.getMessage());
    return new SepExceptionResponse(ex.getMessage());
  }

  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  @ExceptionHandler({Exception.class})
  public SepExceptionResponse handleInternalError(Exception ex) {
    errorEx(ex);
    captureException(ex);
    // A persistence failure's message carries the driver error and the SQL statement. The
    // operator keeps it in the log and in Sentry; the caller gets a fixed text.
    if (hasPersistenceCause(ex)) {
      return new SepExceptionResponse(INTERNAL_ERROR_MESSAGE);
    }
    return new SepExceptionResponse(ex.getMessage());
  }

  private static boolean hasPersistenceCause(Throwable ex) {
    // The visited set ends the walk on a cyclic cause chain, which `initCause` can build.
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable cause = ex; cause != null && visited.add(cause); cause = cause.getCause()) {
      if (cause instanceof DataAccessException
          || cause instanceof TransactionException
          || cause instanceof PersistenceException
          || cause instanceof SQLException) {
        return true;
      }
    }
    return false;
  }

  private ResponseEntity<?> handleArrayLimit(JsonArrayLimitException limit) {
    if (limit.getMember() == null) {
      // A JSON-RPC batch over rpc.batch_size_limit keeps the answer it had before the body was
      // bounded: HTTP 200 with the batch limit error.
      warnLimit("rpc.batch_size_limit", limit.getLimit());
      return ResponseEntity.ok(List.of(RpcUtil.getRpcBatchLimitErrorResponse(limit.getLimit())));
    }
    warnLimit(limit.getMember(), limit.getLimit());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new SepExceptionResponse(
                String.format("The number of records exceeds the limit of %d.", limit.getLimit())));
  }

  /** Logs the refusal: the route, the limit and its value, never the body. */
  private static void warnLimit(String limitName, int limit) {
    String route = "unknown";
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes) {
      HttpServletRequest request =
          ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
      route = request.getMethod() + " " + request.getRequestURI();
    }
    warnF("Rejecting {}: the request body exceeds the limit {} of {}", route, limitName, limit);
  }
}
