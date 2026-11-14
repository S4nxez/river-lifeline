package com.nisarsar.ui.errors;

import static org.assertj.core.api.Assertions.assertThat;

import com.nisarsar.domain.errors.DuplicatedUserError;
import com.nisarsar.domain.errors.ForeignKeyConstraintError;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void aForeignKeyViolationIsAConflictWithItsMessage() {
        ResponseEntity<Object> response =
                handler.handleForeingKeyConstrainError(new ForeignKeyConstraintError("site is referenced"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isEqualTo("site is referenced");
    }

    @Test
    void aDuplicatedUserIsAConflictWithItsMessage() {
        ResponseEntity<Object> response = handler.handleDuplicatedUserError(new DuplicatedUserError("alice exists"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isEqualTo("alice exists");
    }
}
