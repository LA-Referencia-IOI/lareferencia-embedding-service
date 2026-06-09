package org.lareferencia.embedding.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

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
  void rejectsUnsupportedCharactersInStoreName() {
    CreateStoreRequest request = new CreateStoreRequest("qwen3.embedding.4b", "nomic-embed-text", 3, null);

    Set<ConstraintViolation<CreateStoreRequest>> violations = validator.validate(request);

    assertEquals(1, violations.size());
    assertEquals("Store name may only contain letters, numbers, spaces, hyphens, and underscores",
        violations.iterator().next().getMessage());
  }
}