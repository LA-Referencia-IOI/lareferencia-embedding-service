package org.lareferencia.embedding.v1.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CreateStoreRequestTest {

  private static Validator validator;

  @BeforeAll
  static void setUpValidator() {
    ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @Test
  void allowsSpacesInStoreName() {
    CreateStoreRequest request = new CreateStoreRequest("qwen3 embedding 4b", "nomic-embed-text", 3, null);

    Set<ConstraintViolation<CreateStoreRequest>> violations = validator.validate(request);

    assertTrue(violations.isEmpty());
  }

  @Test
  void allowsModelStyleStoreNames() {
    for (String name : java.util.List.of("google/embeddinggemma-2", "embeddinggemma-2:270m")) {
      assertTrue(validator.validate(new CreateStoreRequest(name, "model", 768, null)).isEmpty());
    }
  }

  @Test
  void rejectsUnsupportedCharactersInStoreName() {
    CreateStoreRequest request = new CreateStoreRequest("qwen3.embedding.4b", "nomic-embed-text", 3, null);

    Set<ConstraintViolation<CreateStoreRequest>> violations = validator.validate(request);

    assertEquals(1, violations.size());
    assertEquals(
        "Store name may only contain letters, numbers, spaces, hyphens, underscores, slashes, and colons",
        violations.iterator().next().getMessage());
  }
}
