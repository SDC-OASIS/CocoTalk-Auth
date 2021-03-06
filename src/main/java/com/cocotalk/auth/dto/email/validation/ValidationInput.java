package com.cocotalk.auth.dto.email.validation;

import lombok.*;

import javax.validation.constraints.NotBlank;

@ToString
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
@Setter
public class ValidationInput {
    @NotBlank
    private String email;
    @NotBlank
    private String code;
}