package com.site.xidong.video;

import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.site.xidong.feedback.*;
import com.site.xidong.question.Question;
import com.site.xidong.question.QuestionNotFoundException;
import com.site.xidong.question.QuestionRepository;
import com.site.xidong.security.SiteUserSecurityDTO;
import com.site.xidong.siteUser.SiteUser;
import com.site.xidong.siteUser.SiteUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class VideoService {

    private static final String DEFAULT_THUMBNAIL_URL = "https://dive-s3.s3.ap-northeast-2.amazonaws.com/KakaoTalk_Photo_2021-02-26-01-47-09_m.jpg";
    private final VideoRepository videoRepository;
    private final SiteUserRepository siteUserRepository;
    private final QuestionRepository questionRepository;
    private final AmazonS3Client amazonS3Client;
    private final AwsTranscribe awsTranscribe;
    private final FeedbackService feedbackService;

    @Value("${cloud.aws.s3.bucket}")
    private String bucket;

    @Value("${cloud.aws.cloudfront.domain}")
    private String cloudFrontDomain;


    public VideoReturnDTO getVideo(Long videoId) throws Exception {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(QuestionNotFoundException::new);
        return convertToDTO(video);
    }

    public List<VideoReturnDTO> getOpenVideos() {
        List<Video> videos = videoRepository.findAllOpenVideos();
        return videos.stream()
                .map(this::convertToDTO)
                .toList();
    }

    public List<VideoReturnDTO> getMyVideos() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        SiteUserSecurityDTO siteUserSecurityDTO = (SiteUserSecurityDTO) auth.getPrincipal();
        String username = siteUserSecurityDTO.getUsername();

        List<Video> videos = videoRepository.findMyVideos(username);
        return videos.stream()
                .map(this::convertToDTO)
                .toList();
    }

    public VideoReturnDTO changeVisibility(Long videoId, Boolean isOpen) throws Exception {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        SiteUserSecurityDTO siteUserSecurityDTO = (SiteUserSecurityDTO) auth.getPrincipal();
        SiteUser siteUser = siteUserRepository.findSiteUserByUsername(siteUserSecurityDTO.getUsername())
                .orElseThrow(() -> new Exception("User not found"));

        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new Exception("Video not found"));

        if (!video.getSiteUser().getUsername().equals(siteUser.getUsername())) {
            throw new Exception("수정 권한이 없습니다.");
        }
        video.setOpen(isOpen);
        video.setUpdatedAt(LocalDateTime.now());

        Video updatedVideo = videoRepository.save(video);
        return convertToDTO(updatedVideo);
    }

    public void deleteVideo(Long videoId) throws Exception {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        SiteUserSecurityDTO siteUserSecurityDTO = (SiteUserSecurityDTO) auth.getPrincipal();
        SiteUser siteUser = siteUserRepository.findSiteUserByUsername(siteUserSecurityDTO.getUsername())
                .orElseThrow(() -> new Exception("User not found"));

        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new Exception("Video not found"));

        if (!video.getSiteUser().getUsername().equals(siteUser.getUsername())) {
            throw new Exception("삭제 권한이 없습니다.");
        }

        videoRepository.delete(video);
    }

    private VideoReturnDTO convertToDTO(Video video) {
        return VideoReturnDTO.builder()
                .videoId(video.getId())
                .videoPath(video.getVideoPath())
                .videoName(video.getVideoName())
                .imageUrl(video.getSiteUser().getImageUrl())
                .username(video.getSiteUser().getUsername())
                .nickname(video.getSiteUser().getNickname())
                .thumbnail(video.getThumbnail())
                .question(video.getQuestion().getContents())
                .category(video.getQuestion().getQuestionSet().getCategory())
                .createdAt(video.getCreatedAt())
                .updatedAt(video.getUpdatedAt())
                .isOpen(video.isOpen())
                .build();
    }

    public VideoWithFeedbackDTO createWithThumbnail(Long questionId, String videoKey, Boolean isOpen) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            SiteUserSecurityDTO userDetails = (SiteUserSecurityDTO) auth.getPrincipal();
            SiteUser user = siteUserRepository.findSiteUserByUsername(userDetails.getUsername())
                    .orElseThrow(() -> new IllegalStateException("User not found"));

            Question question = questionRepository.findById(questionId)
                    .orElseThrow(() -> new QuestionNotFoundException());
    
            // S3에서 비디오 가져오기
            S3Object s3Object = amazonS3Client.getObject(bucket, videoKey);
            InputStream videoStream = s3Object.getObjectContent();

            log.info("videoStream is null?: {}", videoStream == null);
            log.info("videoStream available bytes: {}", videoStream.available());

            byte[] videoBytes = IOUtils.toByteArray(videoStream); // 최초 한 번

            // Step 2: 썸네일 생성 및 업로드
            String thumbnailKey = videoKey.replace(".webm", "-thumb.jpg");
            String thumbnailUrl = uploadThumbnailToS3(videoBytes, thumbnailKey);
            log.info("썸네일 URL 생성됨: {}", thumbnailUrl);

            // Step 3: 오디오 추출 및 음성 변환
            // AWS 자격 증명 로드
            AwsCredentialsProvider credentialsProvider = DefaultCredentialsProvider.create();
            log.info("오디오 S3 업로드 시도");

            // S3에 오디오 파일 업로드
            String audioUri = awsTranscribe.uploadToS3(credentialsProvider, videoBytes);
            log.info("오디오 S3 업로드 완료: {}", audioUri);

            // AWS Transcribe 작업 시작
            String jobName = awsTranscribe.startTranscriptionJob(credentialsProvider, audioUri);

            // 변환된 텍스트 가져오기
            String transcriptUri = awsTranscribe.getTranscriptionResult(credentialsProvider, jobName);

            // Json 반환
            String answer = awsTranscribe.parseTranscriptionJson(transcriptUri);
            log.info("변환된 텍스트: {}", answer);


            // Step 4: 비디오 URL 생성
            String videoUrl = String.format("%s/%s", cloudFrontDomain, videoKey);

            // Step 5: Video 객체 생성 및 저장
            Video video = Video.builder()
                    .videoPath(videoUrl)
                    .videoName(videoKey)
                    .siteUser(user)
                    .question(question)
                    .thumbnail(thumbnailUrl)
                    .createdAt(LocalDateTime.now())
                    .isOpen(isOpen)
                    .build();
    
            Video savedVideo = videoRepository.save(video);
            VideoReturnDTO videoReturnDTO = convertToDTO(savedVideo);

            // 피드백 요청 후 저장하기
            AnswerDTO answerDTO = AnswerDTO.builder()
                    .videoId(savedVideo.getId())
                    .answer(answer)
                    .build();
            FeedbackReturnDTO feedbackReturnDTO = feedbackService.getFeedback(answerDTO);

            VideoWithFeedbackDTO videoWithFeedbackDTO = VideoWithFeedbackDTO.builder()
                    .video(videoReturnDTO)
                    .feedback(feedbackReturnDTO)
                    .build();

            return videoWithFeedbackDTO;
        } catch (Exception e) {
            log.error("비디오 처리에 실패했습니다.", e);
            throw new RuntimeException("비디오 처리에 실패했습니다", e);
        }
    }

    private String uploadThumbnailToS3(byte[] videoBytes, String thumbnailKey) {
        FFmpegFrameGrabber grabber = null;
        try {
            log.info("썸네일 생성 시작: {}", thumbnailKey);

            if (videoBytes.length == 0) {
                log.warn("비디오 데이터가 비어있음. Returning default thumbnail.");
                return DEFAULT_THUMBNAIL_URL;
            }

            // FFmpeg 프로세스 빌더 생성 - 첫 프레임을 추출
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg",
                    "-i", "pipe:0",           // 입력은 stdin에서
                    "-ss", "00:00:00.001",    // 시작 직후 (1ms)
                    "-vframes", "1",          // 1개 프레임만 추출
                    "-q:v", "2",              // 품질 설정 (낮을수록 좋은 품질)
                    "-f", "image2",           // 이미지 출력 형식
                    "-c:v", "mjpeg",          // JPEG 인코더
                    "pipe:1"                  // stdout으로 출력
            );

            Process process = pb.start();

            // FFmpeg에 비디오 데이터 전송하는 스레드
            Thread inputThread = new Thread(() -> {
                try (OutputStream ffmpegInput = process.getOutputStream()) {
                    ffmpegInput.write(videoBytes);
                    ffmpegInput.flush();
                } catch (IOException e) {
                    log.error("FFmpeg에 비디오 데이터 전송 중 오류", e);
                }
            });
            inputThread.start();

            // FFmpeg 에러 출력 로깅하는 스레드
            StringBuilder errorOutput = new StringBuilder();
            Thread errorThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        errorOutput.append(line).append("\n");
                    }
                } catch (IOException e) {
                    log.error("FFmpeg 오류 스트림 읽기 실패", e);
                }
            });
            errorThread.start();

            // FFmpeg 표준 출력에서 이미지 읽기
            byte[] thumbnailBytes;
            try (InputStream ffmpegOutput = process.getInputStream()) {
                thumbnailBytes = IOUtils.toByteArray(ffmpegOutput);
            }

            // 스레드 완료 대기
            inputThread.join();
            errorThread.join();

            // 프로세스 완료 대기
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                log.error("FFmpeg 처리 타임아웃");
                return DEFAULT_THUMBNAIL_URL;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0 || thumbnailBytes.length == 0) {
                log.error("FFmpeg 썸네일 생성 실패. 종료 코드: {}, 에러: {}", exitCode, errorOutput);
                return DEFAULT_THUMBNAIL_URL;
            }

            log.info("FFmpeg 썸네일 생성 성공. 크기: {} bytes", thumbnailBytes.length);

            // S3에 업로드
            InputStream thumbnailStream = new ByteArrayInputStream(thumbnailBytes);
            amazonS3Client.putObject(new PutObjectRequest(bucket, thumbnailKey, thumbnailStream, new ObjectMetadata())
                    .withCannedAcl(CannedAccessControlList.PublicRead));

            log.info("썸네일 S3 업로드 완료: {}", thumbnailKey);
            String url = String.format("%s/%s", cloudFrontDomain, thumbnailKey);
            log.info("썸네일 URL: {}", url);
            return url;

        } catch (Exception e) {
            log.warn("썸네일 생성 중 예외 발생. 기본 썸네일 반환", e);
            return DEFAULT_THUMBNAIL_URL;
        }
    }
}
