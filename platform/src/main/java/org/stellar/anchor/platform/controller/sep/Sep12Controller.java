package org.stellar.anchor.platform.controller.sep;

import static org.stellar.anchor.util.Log.*;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import lombok.SneakyThrows;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.stellar.anchor.api.sep.sep12.*;
import org.stellar.anchor.auth.WebAuthJwt;
import org.stellar.anchor.platform.condition.OnAllSepsEnabled;
import org.stellar.anchor.sep12.Sep12Service;
import org.stellar.anchor.util.GsonUtils;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/sep12")
@OnAllSepsEnabled(seps = {"sep12"})
public class Sep12Controller {
  private final Sep12Service sep12Service;

  Sep12Controller(Sep12Service sep12Service) {
    this.sep12Service = sep12Service;
  }

  @SneakyThrows
  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/customer",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.GET})
  public Sep12GetCustomerResponse getCustomer(
      HttpServletRequest request,
      @RequestParam(required = false, name = "type") String type,
      @RequestParam(required = false, name = "id") String id,
      @RequestParam(required = false, name = "account") String account,
      @RequestParam(required = false, name = "memo") String memo,
      @RequestParam(required = false, name = "memo_type") String memoType,
      @RequestParam(required = false, name = "transaction_id") String transactionId,
      @RequestParam(required = false, name = "lang") String lang) {
    debugF(
        "GET /customer type={} id={} account={} memo={}, memoType={}, transactionId={}, lang={}",
        type,
        id,
        account,
        memo,
        memoType,
        transactionId,
        lang);
    WebAuthJwt webAuthJwt = WebAuthJwtHelper.getToken(request);
    Sep12GetCustomerRequest getCustomerRequest =
        Sep12GetCustomerRequest.builder()
            .type(type)
            .id(id)
            .account(account)
            .memo(memo)
            .memoType(memoType)
            .transactionId(transactionId)
            .lang(lang)
            .build();

    return sep12Service.getCustomer(webAuthJwt, getCustomerRequest);
  }

  @SneakyThrows
  @CrossOrigin(origins = "*")
  @ResponseStatus(code = HttpStatus.ACCEPTED)
  @RequestMapping(
      value = "/customer",
      consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_FORM_URLENCODED_VALUE},
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.PUT})
  public Sep12PutCustomerResponse putCustomer(
      HttpServletRequest request, @RequestBody Sep12PutCustomerRequest putCustomerRequest) {
    debug("PUT /customer details:", putCustomerRequest);
    WebAuthJwt webAuthJwt = WebAuthJwtHelper.getToken(request);
    return sep12Service.putCustomer(webAuthJwt, putCustomerRequest);
  }

  @SneakyThrows
  @CrossOrigin(origins = "*")
  @ResponseStatus(code = HttpStatus.ACCEPTED)
  @RequestMapping(
      value = "/customer",
      method = {RequestMethod.PUT},
      consumes = {MediaType.MULTIPART_FORM_DATA_VALUE},
      produces = {MediaType.APPLICATION_JSON_VALUE})
  public Sep12PutCustomerResponse putCustomerMultipart(MultipartHttpServletRequest request) {
    debug("PUT /customer multipart body:", request.getParameterMap());
    Gson gson = GsonUtils.getInstance();
    Map<String, String> requestData = new HashMap<>();
    for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
      requestData.put(entry.getKey(), entry.getValue()[0]);
    }
    Sep12PutCustomerRequest putCustomerRequest =
        gson.fromJson(gson.toJson(requestData), Sep12PutCustomerRequest.class);

    // Extract binary fields
    MultiValueMap<String, MultipartFile> files = request.getMultiFileMap();
    for (java.lang.reflect.Field field : Sep12PutCustomerRequest.class.getDeclaredFields()) {
      if (field.getType() == byte[].class) {
        field.setAccessible(true);
        SerializedName serializedName = field.getAnnotation(SerializedName.class);
        String fieldName = (serializedName != null) ? serializedName.value() : field.getName();

        MultipartFile file = files.getFirst(fieldName);
        if (file != null) {
          field.set(putCustomerRequest, file.getBytes());
        }
      }
    }

    WebAuthJwt webAuthJwt = WebAuthJwtHelper.getToken(request);
    return sep12Service.putCustomer(webAuthJwt, putCustomerRequest);
  }

  @SneakyThrows
  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/customer/{account}",
      consumes = {MediaType.APPLICATION_JSON_VALUE},
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.DELETE})
  @ResponseStatus(code = HttpStatus.OK)
  public void deleteCustomer(
      HttpServletRequest request,
      @PathVariable String account,
      @RequestBody(required = false) Sep12DeleteCustomerRequest body) {
    WebAuthJwt webAuthJwt = WebAuthJwtHelper.getToken(request);
    String memo = body != null ? body.getMemo() : null;
    String memoType = body != null ? body.getMemoType() : null;
    debugF(
        "DELETE /customer requestURI={} account={} body={}",
        request.getRequestURI(),
        account,
        body);
    sep12Service.deleteCustomer(webAuthJwt, account, memo, memoType);
  }

  @SneakyThrows
  @CrossOrigin(origins = "*")
  @RequestMapping(
      value = "/customer/{account}",
      consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE},
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.DELETE})
  @ResponseStatus(code = HttpStatus.OK)
  public void deleteCustomer(
      HttpServletRequest request,
      @PathVariable String account,
      @RequestParam(required = false) String memo,
      @RequestParam(required = false, name = "memo_type") String memoType) {
    WebAuthJwt webAuthJwt = WebAuthJwtHelper.getToken(request);
    debugF("DELETE /customer requestURI={} account={}", request.getRequestURI(), account);
    sep12Service.deleteCustomer(webAuthJwt, account, memo, memoType);
  }
}
