package com.blackbox.activity;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class ActivityStream {

    private final List<SseEmitter> clients = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);

        clients.add(emitter);

        emitter.onCompletion(() -> clients.remove(emitter));
        emitter.onTimeout(() -> clients.remove(emitter));
        emitter.onError(error -> clients.remove(emitter));

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("Engineering Blackbox connected"));
        } catch (IOException e) {
            clients.remove(emitter);
        }

        return emitter;
    }

    public void publish(Activity activity) {
        for (SseEmitter emitter : clients) {
            try {
                emitter.send(
                        SseEmitter.event()
                                .name("activity")
                                .id(activity.id())
                                .data(activity)
                );
            } catch (Exception e) {
                clients.remove(emitter);
            }
        }
    }

    public int connectedClients() {
        return clients.size();
    }
}
