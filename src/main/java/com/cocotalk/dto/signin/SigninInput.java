package com.cocotalk.dto.signin;

import lombok.*;

import javax.validation.constraints.NotBlank;

@ToString
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
public class SigninInput {
    @NotBlank
    private String cid;
    @NotBlank
    private String password;
}
