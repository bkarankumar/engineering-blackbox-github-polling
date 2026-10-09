package com.blackbox.api;

import com.blackbox.activity.ActivityStream;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/stream")
@RequiredArgsConstructor
public class StreamController {

    private final ActivityStream stream;

    @GetMapping(
            value = "/activities",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter activities() {
        return stream.subscribe();
    }
}
