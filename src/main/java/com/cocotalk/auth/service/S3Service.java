package com.cocotalk.auth.service;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import com.cocotalk.auth.dto.common.response.ResponseStatus;
import com.cocotalk.auth.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 *
 * S3에 파일을 올리는 서비스
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class S3Service {

    private final AmazonS3 amazonS3;

    @Value("${cloud.aws.cloudfront.domain}")
    private String cloudFrontDomain;

    @Value("${cloud.aws.s3.bucket}")
    private String bucket;

    /**
     * 유저 프로필에 들어갈 사진을 S3에 업로드합니다.
     * @param img S3에 업로드할 사진
     * @param imgThumb S3에 업로드할 사진의 썸네일
     * @param userId 유저의 id
     *
     * @return 업로드된 사진의 url
     */
    public String uploadProfileImg(MultipartFile img, MultipartFile imgThumb, Long userId) {
        //프로필 업로드
        String extension = StringUtils.getFilenameExtension(img.getOriginalFilename());
        String imgPath = "user_profile/" + userId + "/profile/" + LocalDateTime.now();
        String originUrl = uploadFile(img,imgPath+"."+extension);
        //썸네일 업로드
        String thumbUrl = uploadFile(imgThumb, imgPath+"_th."+extension);
        log.info("[S3Service/originUrl] : "+originUrl);
        log.info("[S3Service/thumbUrl] : "+thumbUrl);
        return originUrl;
    }

    /**
     * S3에 파일을 업로드합니다
     * @param file S3에 업로드할 파일
     * @param filePath S3에 업로드될 경로
     *
     * @return 업로드된 file의 url
     */
    private String uploadFile(MultipartFile file, String filePath) {
        try {
            amazonS3.putObject(new PutObjectRequest(bucket, filePath, file.getInputStream(), null)
                    .withCannedAcl(CannedAccessControlList.PublicRead));
            log.info("[S3Service/uploadImage] : " + filePath + " is uploaded");
            return cloudFrontDomain+"/"+filePath;
        } catch (IOException e) {
            throw new CustomException(ResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }


}