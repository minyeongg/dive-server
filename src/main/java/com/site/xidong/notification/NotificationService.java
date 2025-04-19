package com.site.xidong.notification;

import com.site.xidong.security.SiteUserSecurityDTO;
import com.site.xidong.siteUser.SiteUser;
import com.site.xidong.siteUser.SiteUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {
    private final static Long DEFAULT_TIMEOUT = 60 * 60 * 1000L; // 1시간
    private final static String CONNECTION = "connection";
    private final static String NEW_COMMENT = "new_comment";

    private final SiteUserRepository siteUserRepository;
    private final EmitterRepository emitterRepository;

    public SseEmitter connectNotification() throws Exception {
        log.info("SSE 연결 프로세스 시작");

        try {
            log.info("사용자 인증 정보 조회 중");
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            SiteUserSecurityDTO siteUserSecurityDTO = (SiteUserSecurityDTO) auth.getPrincipal();

            log.info("사용자 정보 DB 조회 중");
            SiteUser siteUser = siteUserRepository.findSiteUserByUsername(siteUserSecurityDTO.getUsername()).get();
            String username = siteUser.getUsername();
            log.info("사용자 정보 조회 완료: {}", username);

            log.info("SseEmitter 객체 생성 중");
            // 새로운 SseEmitter를 만든다
            SseEmitter sseEmitter = new SseEmitter(DEFAULT_TIMEOUT);
            log.info("SseEmitter 객체 생성 완료");

            log.info("EmitterRepository에 저장 중");
            // username으로 SseEmitter를 저장
            emitterRepository.save(username, sseEmitter);
            log.info("Emitter 저장 - key: {}, emitter hash: {}", username, System.identityHashCode(sseEmitter));


            log.info("이벤트 핸들러 등록 중");
            // 세션이 종료될 경우 저장한 SseEmitter를 삭제한다.
            sseEmitter.onCompletion(() -> {
                log.info("SSE 연결 완료 이벤트 발생 - {} 사용자의 emitter 삭제", username);
                emitterRepository.delete(username);
            });
            sseEmitter.onTimeout(() -> {
                log.info("SSE 연결 타임아웃 발생 - {} 사용자의 emitter 삭제", username);
                emitterRepository.delete(username);
            });
            log.info("이벤트 핸들러 등록 완료");

            log.info("초기 SSE 이벤트 전송 중");
            // 503 Service Unavailable 오류가 발생하지 않도록 첫 데이터를 보낸다.
            try {
                sseEmitter.send(SseEmitter.event().id("connection-established-001").name(CONNECTION).data("Connection completed!"));
                log.info("초기 SSE 이벤트 전송 완료");
            } catch (IOException exception) {
                log.error("SSE 이벤트 전송 실패: {}", exception.getMessage());
                throw new Exception("Failed to Connect SSE");
            }

            log.info("SSE 연결 프로세스 완료 - {} 사용자의 연결 설정됨", username);
            return sseEmitter;
        } catch (Exception e) {
            log.error("SSE 연결 중 오류 발생: {}", e.getMessage());
            throw e;
        }
    }

    public void send(String username, String contents) {
        // username으로 SseEmitter를 찾아 이벤트를 발생 시킨다.
        log.info("Emitter 조회 시도 - key: {}", username);
        emitterRepository.get(username).ifPresentOrElse(sseEmitter -> {
            try {
                sseEmitter.send(SseEmitter.event().id("new-comment").name(NEW_COMMENT).data("새로운 댓글이 달렸습니다: " + contents));
                log.info("Emitter 찾음 - hash: {}", System.identityHashCode(sseEmitter));
            } catch (IOException exception) {
                // IOException이 발생하면 저장된 SseEmitter를 삭제하고 예외를 발생시킨다.
                emitterRepository.delete(username);
                throw new Error("Failed to Connect SSE");
            }
        }, () -> log.info("Emitter 없음 - {}", username));
    }
}
