package org.stellar.anchor.platform.controller.custody;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.stellar.anchor.platform.custody.CustodyEventService;

@RestController
@AllArgsConstructor
@CrossOrigin(origins = "*")
public class CustodyWebhookController {

  private final CustodyEventService custodyEventService;

  @SneakyThrows
  @CrossOrigin(origins = "*")
  @ResponseStatus(code = HttpStatus.OK)
  @RequestMapping(
      value = "/webhook",
      method = {RequestMethod.POST},
      consumes = {MediaType.APPLICATION_JSON_VALUE},
      produces = {MediaType.APPLICATION_JSON_VALUE})
  public ResponseEntity<Void> handleEvent(
      @RequestBody String eventObject, @RequestHeader Map<String, String> headers) {
    custodyEventService.handleEvent(eventObject, headers);
    return ResponseEntity.ok().build();
  }
}
