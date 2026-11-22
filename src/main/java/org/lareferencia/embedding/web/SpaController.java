package org.lareferencia.embedding.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

/** Forward only known client routes, never API, operational or asset paths. */
@Controller
public class SpaController {
  @GetMapping(
      value = {
        "/{page:collections|collection-create|document-edit|imports|profile-create|indexation-create|indexation-actions|profiles|indexations|search|vectors|jobs|legacy|operations|settings}",
        "/{page:collections|collection-create|document-edit|imports|profile-create|indexation-create|indexation-actions|profiles|indexations|search|vectors|jobs|legacy|operations|settings}/**"
      },
      produces = "text/html")
  public String index(HttpServletRequest request) {
    if (request.getRequestURI().contains(".")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return "forward:/index.html";
  }
}
