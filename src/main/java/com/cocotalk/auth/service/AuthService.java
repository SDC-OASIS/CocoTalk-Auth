package com.cocotalk.auth.service;

import com.cocotalk.auth.dto.common.payload.TokenPayload;
import com.cocotalk.auth.dto.common.request.chat.CrashRequest;
import com.cocotalk.auth.dto.common.request.push.FCMTokenRequest;
import com.cocotalk.auth.dto.common.payload.ProfilePayload;
import com.cocotalk.auth.exception.CustomException;
import com.cocotalk.auth.dto.common.*;
import com.cocotalk.auth.dto.signup.SignupInput;
import com.cocotalk.auth.repository.UserRepository;
import com.cocotalk.auth.dto.common.response.ResponseStatus;
import com.cocotalk.auth.dto.email.issue.IssueInput;
import com.cocotalk.auth.dto.email.issue.IssueOutput;
import com.cocotalk.auth.dto.email.validation.ValidationInput;
import com.cocotalk.auth.dto.signin.SigninInput;
import com.cocotalk.auth.dto.signup.SignupOutput;
import com.cocotalk.auth.entity.User;
import com.cocotalk.auth.entity.mapper.UserMapper;
import com.cocotalk.auth.dto.common.response.Response;
import com.cocotalk.auth.utils.JwtUtils;
import com.cocotalk.auth.utils.SHA256Utils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import javax.mail.Message;
import javax.mail.internet.MimeMessage;
import java.time.LocalDateTime;

import static com.cocotalk.auth.dto.common.response.ResponseStatus.*;


@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {
    private final UserMapper userMapper;
    private final UserRepository userRepository;
    private final RedisService redisService;
    private final JavaMailSender mailSender;
    private final S3Service s3Service;

    @Value("${api.gateway}")
    String gatewayAPI;

    @Value("${mail.exp}")
    long mailCodeExp;

    /**
     * 로그인
     *
     * @param clientInfo 요청 클라이언트의 정보 ( MOBILE/WEB을 별도로 관리하기 위해 필요 )
     * @param signinInput 로그인에 필요한 요청 모델
     * @return 발급된 access Token과 refresh Token
     */
    public ResponseEntity<Response<TokenDto>> signin(ClientInfo clientInfo, SigninInput signinInput) {
        // 1. user 정보 가져오기
        log.info("[signin/ClientInfo] : "+ clientInfo);
        log.info("[signin/SigninInput] : "+ signinInput);
        User user;
        try {
            user = userRepository.findByCid(signinInput.getCid()).orElse(null);
            if (user == null || !SHA256Utils.getEncrypt(signinInput.getPassword()).equals(user.getPassword())) {
                return ResponseEntity.status(HttpStatus.OK).body(new Response<>(BAD_REQUEST));
            }
            user.setLoggedinAt(LocalDateTime.now());
        } catch (Exception e){
            e.printStackTrace();
            throw new CustomException(DATABASE_ERROR, e);
        }

        // 2. token 생성
        String accessToken;
        String refreshToken;
        try {
            accessToken = JwtUtils.createAccessToken(user.getId(), signinInput.getFcmToken());
            refreshToken = JwtUtils.createRefreshToken(user.getId(), signinInput.getFcmToken());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.OK).body(new Response<>(BAD_REQUEST));
        }

        // 3. push 서버에 fcm token 갱신하라고 알려주기
        setFcmToken(user.getId(), signinInput.getFcmToken(), clientInfo);

        // 4. redis에 refresh token 기록
        redisService.setRefreshToken(clientInfo.getClientType(), user.getId(), refreshToken);

        // 5. chat 서버에 기존에 로그인 중인 device 강제종료 요청하기 (기기별 동시 로그인 제한)
        sendCrashRequest(accessToken, user.getId(), signinInput.getFcmToken(), clientInfo.getClientType());

        // 6. 결과 return
        TokenDto tokenDto = TokenDto.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
        return ResponseEntity.status(HttpStatus.OK).body(new Response<>(tokenDto, SUCCESS));
    }
    
    /**
     * 회원가입
     *
     * @param signupInput 회원가입에 필요한 요청 모델
     * @return 회원가입된 유저의 정보
     */
    @Transactional
    public ResponseEntity<Response<SignupOutput>> signup(SignupInput signupInput) {
        log.info("[signup/signupInput] : "+signupInput);
        // 2. 유저 생성
        User user;
        try {
            // 중복 제어
            boolean exists = userRepository.existsByCid(signupInput.getCid())
                    || userRepository.existsByPhone(signupInput.getPhone())
                    || userRepository.existsByEmail(signupInput.getEmail());
            if (exists) {
                return ResponseEntity.status(HttpStatus.OK).body(new Response<>(EXISTS_INFO));
            }

            // pk 생성
            user = userRepository.save(userMapper.toEntity(signupInput));

            String imgUrl = null;
            // 이미지가 있을경우 s3에 저장
            if(signupInput.getProfileImg()!=null && signupInput.getProfileImgThumb()!=null)
                imgUrl = s3Service.uploadProfileImg(signupInput.getProfileImg(), signupInput.getProfileImgThumb(), user.getId());

            // user에 적용
            ProfilePayload profile = ProfilePayload.builder().profile(imgUrl).build();
            user.setProfile(ProfilePayload.toJSON(profile)); // object -> json
            user.setPassword(SHA256Utils.getEncrypt(signupInput.getPassword()));
            userRepository.save(user);

        } catch (Exception e) {
            log.error("[signup/post] database error", e);
            throw new CustomException(DATABASE_ERROR);
        }
        // 3. 결과 return
        SignupOutput signupOutput = userMapper.toDto(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(new Response<>(signupOutput, CREATED));
    }

    /**
     * 로그이웃
     *
     * @param clientInfo 요청 클라이언트의 정보 ( MOBILE/WEB을 별도로 관리하기 위해 필요 )
     */
    public ResponseEntity<Response<Object>> signout(ClientInfo clientInfo) {
        String refreshToken = JwtUtils.getRefreshToken();
        if(refreshToken!=null) {
            Long userId = JwtUtils.getPayload(refreshToken).getUserId();
            if(userId!=null) redisService.deleteRefreshToken(clientInfo.getClientType(), userId);
        }
        /*
            소켓 서버에서, 다른 기기 로그아웃 처리 요청
         */
        return ResponseEntity.status(HttpStatus.OK).body(new Response<>(null, SUCCESS));
    }

    /**
     * 요청 refresh token이 redis의 refresh token값과 일치한 경우 token 재발급
     *
     * @param clientInfo 요청 클라이언트의 정보 ( MOBILE/WEB을 별도로 관리하기 위해 필요 )
     * @return 발급된 access Token과 refresh Token
     */
    public ResponseEntity<Response<TokenDto>> reissue(ClientInfo clientInfo) {
        ClientType clientType = clientInfo.getClientType();
        String refreshToken = JwtUtils.getRefreshToken();
        if(refreshToken==null) {
            log.error("[reissue] X-REFRESH-TOKEN is null");
            return ResponseEntity.status(HttpStatus.OK).body(new Response<>(UNAUTHORIZED));
        }
        try{
            // 1. refresh token이 서버와 일치하는지 확인
            Long userId = JwtUtils.getPayload(refreshToken).getUserId();
            String storeRefreshToken = redisService.getRefreshToken(clientType, userId);
            if(!refreshToken.equals(storeRefreshToken)) {
                log.error("[reissue] refreshToken is not equals as storeRefreshToken");
                log.info("[reissue] request refresh token is "+ refreshToken);
                log.info("[reissue] store   refresh token is "+ storeRefreshToken);
                return ResponseEntity.status(HttpStatus.OK).body(new Response<>(UNAUTHORIZED));
            }

            // 2. token 생성
            String fcmToken = JwtUtils.getPayload(refreshToken).getFcmToken();
            String newRefreshToken = JwtUtils.createRefreshToken(userId, fcmToken);

            // 3. redis에 refresh token 갱신
            redisService.setRefreshToken(clientType,userId,newRefreshToken);
            
            // 4. 결과 반환
            TokenDto token = TokenDto.builder()
                    .accessToken(JwtUtils.createAccessToken(userId,fcmToken))
                    .refreshToken(newRefreshToken)
                    .build();
            return ResponseEntity.status(HttpStatus.OK).body(new Response<>(token, SUCCESS));
        }catch (Exception e){
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.OK).body(new Response<>(UNAUTHORIZED));
        }
    }

    /**
     * 이메일로 인증코드 전송
     *
     * @param issueInput 인증 코드를 보낼 이메일이 담긴 요청 모델
     * @return 전송한 인증코드의 만료시간이 담긴 모델
     */
    public ResponseEntity<Response<IssueOutput>> sendMail(IssueInput issueInput) {
        log.info("[sendMail/IssueInput] : "+issueInput);
        IssueOutput emailOutput;
        try {
            // 1. 인증 메일 전송
            String generatedString = RandomStringUtils.random(10, true, true);
            MimeMessage message = mailSender.createMimeMessage();
            message.addRecipients(Message.RecipientType.TO, issueInput.getEmail());//보내는 대상
            message.setSubject("[코코톡] 이메일 인증번호입니다.");
            String msgg= "<div style='margin:100px;'>" +
                    "<h1> 안녕하세요 코코톡입니다 </h1>" +
                    "<br>" +
                    "<p>아래 코드를 입력해주세요<p>" +
                    "<br>" +
                    "<div align='center' style='background-color: #aecdb3a1; border-radius: 60% 10%; padding: 10px; font-family:verdana';>" +
                    "<h3 style='color:#747474;'> 코드입니다</h3>" +
                    "<div style='font-size:130%'>" +
                    "CODE : <strong>" +
                    generatedString +
                    "</strong><div><br/>" +
                    "</div>";
            message.setText(msgg, "utf-8", "html");

            LocalDateTime expirationDate = LocalDateTime.now().plusSeconds(mailCodeExp);
            emailOutput = IssueOutput.builder().expirationDate(expirationDate).build();
            mailSender.send(message);

            // 2. redis에 code 기록
            redisService.setEmailCode(issueInput.getEmail(), generatedString);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.OK).body(new Response<>(BAD_REQUEST));
        }
        // 3. 결과 return
        return ResponseEntity.status(HttpStatus.OK).body(new Response<>(emailOutput, SUCCESS));
    }

    /**
     * 해당 이메일의 인증코드가 sendMail 함수로 전송한 인증코드와 일치하는지 검증
     *
     * @param validationInput 이메일과 인증코드가 담긴 요청 모델
     * @return 해당 이메일의 인증코드가 유효한지에 대한 결과
     */
    public ResponseEntity<Response<ValidationDto>> checkMail(ValidationInput validationInput) {
        log.info("[checkMail/ValidationInput] : "+validationInput);
        String code = redisService.getEmailCode(validationInput.getEmail());
        Boolean res = code!=null && validationInput.getCode().equals(code);
        ValidationDto validationOutput = ValidationDto.builder().isValid(res).build();
        return ResponseEntity.status(HttpStatus.OK).body(new Response<>(validationOutput, SUCCESS));
    }


    /**
     * 마지막으로 로그인한 기기 검증
     * request의 accesstoken 속 fcmtoken과 redis에 보관된 마지막 접속자의 refreshtoken속 fcmtoken을 비교
     *
     * @param clientInfo 요청자의 client 정보 ( MOBILE/WEB을 별도로 관리하기 위해 필요 )
     * @return 마지막으로 로그인힌 기기가 맞는지에 대한 결과
     */
    public ResponseEntity<Response<ValidationDto>> checkLastly(ClientInfo clientInfo) {
        String accessToken = JwtUtils.getAccessToken();
        if(accessToken==null)
            return ResponseEntity.status(HttpStatus.OK).body(new Response<>(ResponseStatus.UNAUTHORIZED));
        /*
         [인증 요청한 기기]의 FCM Token과
         서버에 기록된 [마지막 로그인 기기]의 FCM Token
         일치하는지 비교
         */
        TokenPayload currTP = JwtUtils.getPayload(accessToken);

        String lastlyRToken = redisService.getRefreshToken(clientInfo.getClientType(), currTP.getUserId()); //마지막 로그인 유저의 refresh token
        String lastlyFToken = JwtUtils.getPayload(lastlyRToken).getFcmToken(); //lastlyRToken로 마지막 로그인한 기기 fcm token 구함
        Boolean res = currTP.getFcmToken().equals(lastlyFToken);
        ValidationDto validationDto = ValidationDto.builder().isValid(res).build();
        return ResponseEntity.status(HttpStatus.OK).body(new Response<>(validationDto, SUCCESS));
    }

    /**
     * Push 서버로 fcm token을 갱신해달라는 요청을 보냄
     *
     * @param userId fcmToken을 갱신할 userId
     * @param fcmToken 갱신할 새로운 fcmToken
     * @param clientInfo 요청자의 client 정보 ( MOBILE/WEB을 별도로 관리하기 위해 필요 )
     */
    private void setFcmToken(Long userId, String fcmToken, ClientInfo clientInfo){
        FCMTokenRequest fcmTokenDto = FCMTokenRequest.builder()
                .userId(userId)
                .fcmToken(fcmToken)
                .build();
        WebClient webClient = WebClient.create(gatewayAPI+"/push");
        try{
             String response =  webClient.post()
                      .uri("/device")
                      .header("User-Agent", clientInfo.getAgent())
                      .header("X-Forwarded-For", clientInfo.getIp())
                      .contentType(MediaType.APPLICATION_JSON)
                      .bodyValue(fcmTokenDto)
                      .retrieve()
                      .bodyToMono(String.class).block();
            log.info("[setFcmToken/result] :" + response);
        }catch (Exception e){
            throw new CustomException(SERVER_ERROR,e);
        }
    }

    /**
     * chat server에게 현재 로그인한 기기를 제외하고 같은 타입의 기기는 로그아웃 처리해 달라는 요청을 보냄
     * ex) 모바일로 로그인 했으면 다른 모바일 기기는 로그인이 해제되어야 함
     *
     * @param userId 현재 로그인한 user의 id
     * @param fcmToken 현재 로그인한 user의 device 정보 (FCM TOKEN)
     * @param clientType 현재 로그인한 user의 client type (WEB or MOBILE)
     */
    private void sendCrashRequest(String accessToken, Long userId, String fcmToken, ClientType clientType){
        CrashRequest crashRequest = CrashRequest.builder()
                .clientType(clientType.name())
                .userId(userId)
                .fcmToken(fcmToken)
                .build();
        WebClient webClient = WebClient.create(gatewayAPI+"/chat");
        try{
            webClient.post()
                    .uri("/crash")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-ACCESS-TOKEN", accessToken)
                    .bodyValue(crashRequest)
                    .retrieve()
                    .bodyToMono(String.class).block();
        }catch (Exception e){
            throw new CustomException(SERVER_ERROR,e);
        }
    }
}
