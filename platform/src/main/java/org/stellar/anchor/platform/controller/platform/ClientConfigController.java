package org.stellar.anchor.platform.controller.platform;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

  @ResponseStatus(code = HttpStatus.OK)
  @RequestMapping(
      value = "/clients/{name}",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.GET})
  public ClientConfigResponse getClient(@PathVariable(name = "name") String name)
      throws AnchorException {
    return clientConfigService.get(name);
  }

  @ResponseStatus(code = HttpStatus.OK)
  @RequestMapping(
      value = "/clients",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.GET})
  public List<ClientConfigResponse> listClients() {
    return clientConfigService.list();
  }

  @ResponseStatus(code = HttpStatus.OK)
  @RequestMapping(
      value = "/clients/custodial",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.GET})
  public List<ClientConfigResponse> listCustodialClients() {
    return clientConfigService.listCustodial();
  }

  @ResponseStatus(code = HttpStatus.OK)
  @RequestMapping(
      value = "/clients/non-custodial",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.GET})
  public List<ClientConfigResponse> listNonCustodialClients() {
    return clientConfigService.listNonCustodial();
  }

  @ResponseStatus(code = HttpStatus.NO_CONTENT)
  @RequestMapping(
      value = "/clients/{name}",
      method = {RequestMethod.DELETE})
  public void deleteClient(@PathVariable(name = "name") String name) throws AnchorException {
    clientConfigService.delete(name);
  }

  @ResponseStatus(code = HttpStatus.OK)
  @RequestMapping(
      value = "/clients/{name}/destination-accounts/{account}",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.POST})
  public ClientConfigResponse addDestinationAccount(
      @PathVariable(name = "name") String name, @PathVariable(name = "account") String account)
      throws AnchorException {
    return clientConfigService.addDestinationAccount(name, account);
  }

  @ResponseStatus(code = HttpStatus.NO_CONTENT)
  @RequestMapping(
      value = "/clients/{name}/destination-accounts/{account}",
      method = {RequestMethod.DELETE})
  public void removeDestinationAccount(
      @PathVariable(name = "name") String name, @PathVariable(name = "account") String account)
      throws AnchorException {
    clientConfigService.removeDestinationAccount(name, account);
  }

  @ResponseStatus(code = HttpStatus.OK)
  @RequestMapping(
      value = "/clients/{name}/signing-keys/{signingKey}",
      produces = {MediaType.APPLICATION_JSON_VALUE},
      method = {RequestMethod.POST})
  public ClientConfigResponse addSigningKey(
      @PathVariable(name = "name") String name,
      @PathVariable(name = "signingKey") String signingKey)
      throws AnchorException {
    return clientConfigService.addSigningKey(name, signingKey);
  }

  @ResponseStatus(code = HttpStatus.NO_CONTENT)
  @RequestMapping(
      value = "/clients/{name}/signing-keys/{signingKey}",
      method = {RequestMethod.DELETE})
  public void removeSigningKey(
      @PathVariable(name = "name") String name,
      @PathVariable(name = "signingKey") String signingKey)
      throws AnchorException {
    clientConfigService.removeSigningKey(name, signingKey);
  }
}
