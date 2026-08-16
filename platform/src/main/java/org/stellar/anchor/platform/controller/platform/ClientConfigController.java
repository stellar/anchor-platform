package org.stellar.anchor.platform.controller.platform;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.stellar.anchor.api.exception.AnchorException;
import org.stellar.anchor.platform.service.ClientConfigService;

@RestController
public class ClientConfigController {

  private final ClientConfigService clientConfigService;

  ClientConfigController(ClientConfigService clientConfigService) {
    this.clientConfigService = clientConfigService;
  }

  @CrossOrigin(origins = "*")
  @ResponseStatus(code = HttpStatus.OK)
  @RequestMapping(
      value = "/clients/{name}",
      consumes = {MediaType.APPLICATION_JSON_VALUE},
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.PUT})
  public ClientConfigResponse upsertClient(
      @PathVariable(name = "name") String name, @RequestBody ClientConfigRequest request)
      throws AnchorException {
    return clientConfigService.upsert(name, request);
  }

  @CrossOrigin(origins = "*")
  @ResponseStatus(code = HttpStatus.OK)
  @RequestMapping(
      value = "/clients/{name}",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.GET})
  public ClientConfigResponse getClient(@PathVariable(name = "name") String name)
      throws AnchorException {
    return clientConfigService.get(name);
  }

  @CrossOrigin(origins = "*")
  @ResponseStatus(code = HttpStatus.OK)
  @RequestMapping(
      value = "/clients",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.GET})
  public List<ClientConfigResponse> listClients() {
    return clientConfigService.list();
  }

  @CrossOrigin(origins = "*")
  @ResponseStatus(code = HttpStatus.NO_CONTENT)
  @RequestMapping(
      value = "/clients/{name}",
      method = {RequestMethod.DELETE})
  public void deleteClient(@PathVariable(name = "name") String name) throws AnchorException {
    clientConfigService.delete(name);
  }
}
