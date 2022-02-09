package com.cocotalk.auth.dto.common;

import com.cocotalk.auth.dto.common.response.ResponseStatus;
import com.cocotalk.auth.exception.CustomException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.*;

@Builder
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ProfilePayload {
    private String profile;
    private String background;
    private String message;

    public static String toJSON(ProfilePayload profilePayload){
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.writeValueAsString(profilePayload);
        } catch (Exception e) {
            throw new CustomException(ResponseStatus.PARSE_ERROR);
        }
    }

    public static ProfilePayload toObject(String jsonString){
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readValue(jsonString, ProfilePayload.class);
        } catch (Exception e) {
            throw new CustomException(ResponseStatus.PARSE_ERROR);
        }
    }
}
