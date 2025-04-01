package org.stellar.anchor.platform.controller;

import static io.sentry.Sentry.*;
import static org.stellar.anchor.util.Log.*;

import io.sentry.SentryLevel;
import jakarta.transaction.NotSupportedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.stellar.anchor.api.custody.CustodyExceptionResponse;
import org.stellar.anchor.api.exception.*;
import org.stellar.anchor.api.exception.custody.CustodyBadRequestException;
import org.stellar.anchor.api.exception.custody.CustodyNotFoundException;
import org.stellar.anchor.api.exception.custody.CustodyServiceUnavailableException;
import org.stellar.anchor.api.exception.custody.CustodyTooManyRequestsException;
import org.stellar.anchor.api.sep.CustomerInfoNeededResponse;
import org.stellar.anchor.api.sep.SepExceptionResponse;

public abstract class AbstractControllerExceptionHandler {
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
  @ResponseStatus(value = HttpStatus.BAD_REQUEST)
  public SepExceptionResponse handleRandomException(HttpMessageNotReadableException ex) {
    debugF("Spring is unable to read HTTP message: {}", ex.getMessage());
    return new SepExceptionResponse("Your request body is wrong in some way.");
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

  @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
  @ExceptionHandler({NotSupportedException.class})
  public SepExceptionResponse handleNotImplementedError(Exception ex) {
    errorF("Not implemented: {}", ex.getMessage());
    captureException(ex);
    return new SepExceptionResponse(ex.getMessage());
  }

  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler({CustodyBadRequestException.class})
  public CustodyExceptionResponse handleCustodyBadRequest(AnchorException ex) {
    debugF("Bad request (custody server): {}", ex.getMessage());
    return new CustodyExceptionResponse(ex.getMessage());
  }

  @ResponseStatus(value = HttpStatus.NOT_FOUND)
  @ExceptionHandler({CustodyNotFoundException.class})
  public CustodyExceptionResponse handleCustodyNotFound(AnchorException ex) {
    traceF("Resource not found (custody server): {}", ex.getMessage());
    return new CustodyExceptionResponse(ex.getMessage());
  }

  @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
  @ExceptionHandler({CustodyTooManyRequestsException.class})
  public CustodyExceptionResponse handleCustodyTooManyRequestsError(AnchorException ex) {
    errorF("Too many requests (custody server): {}", ex.getMessage());
    captureException(ex);
    return new CustodyExceptionResponse(ex.getMessage());
  }

  @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
  @ExceptionHandler({CustodyServiceUnavailableException.class})
  public CustodyExceptionResponse handleCustodyServiceUnavailableError(AnchorException ex) {
    errorEx(ex);
    captureException(ex);
    return new CustodyExceptionResponse(ex.getMessage());
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
    return new SepExceptionResponse(ex.getMessage());
  }
}
