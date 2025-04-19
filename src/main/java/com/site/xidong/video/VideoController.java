package com.site.xidong.video;

import com.site.xidong.utils.S3Uploader;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.joda.time.DateTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@Log4j2
@RequiredArgsConstructor
@RequestMapping("/video")
public class VideoController {

    private final S3Uploader s3Uploader;
    private final VideoService videoService;

    @GetMapping("/presigned")
    public ResponseEntity<Map<String, String>> getPresignedUrl(
            @RequestParam Long questionId) {
        try {
            String fileName = DateTime.now() + "_video.webm";
            Map<String, String> response = s3Uploader.generatePresignedUrl(fileName);
            response.put("videoKey", fileName);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to generate presigned URL", e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PostMapping("/complete-upload")
    public ResponseEntity<VideoWithFeedbackDTO> completeUpload(
            @RequestBody VideoUploadCompleteRequest request) {
        try {
            VideoWithFeedbackDTO videoReturnDTO = videoService.createWithThumbnail(
                    request.getQuestionId(),
                    request.getVideoKey(),
                    request.isOpen()
            );
            return ResponseEntity.ok(videoReturnDTO);
        } catch (Exception e) {
            log.error("Failed to complete upload", e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    @GetMapping("/{videoId}")
    public ResponseEntity<VideoReturnDTO> getVideo(@PathVariable Long videoId) {
        VideoReturnDTO videoReturnDTO;
        try {
            videoReturnDTO = videoService.getVideo(videoId);
        } catch (Exception e) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.status(HttpStatus.OK).body(videoReturnDTO);
    }

    @GetMapping("/all")
    public ResponseEntity<List<VideoReturnDTO>> getOpenVideos() {
        List<VideoReturnDTO> videoReturnDTOs = videoService.getOpenVideos();
        return ResponseEntity.status(HttpStatus.OK).body(videoReturnDTOs);
    }

    @GetMapping("/myVideos")
    public ResponseEntity<List<VideoReturnDTO>> getMyVideos() {
        List<VideoReturnDTO> videoReturnDTOs = videoService.getMyVideos();
        return ResponseEntity.status(HttpStatus.OK).body(videoReturnDTOs);
    }

    @PutMapping("/{videoId}/change/visibility")
    public ResponseEntity<?> change(@PathVariable Long videoId, @RequestBody Boolean isOpen) throws Exception {
        try {
            videoService.changeVisibility(videoId, isOpen);
        } catch (Exception e) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @DeleteMapping("/{videoId}/delete")
    public ResponseEntity<?> delete(@PathVariable Long videoId) throws Exception {
        try {
            videoService.deleteVideo(videoId);
        } catch (Exception e) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }
}