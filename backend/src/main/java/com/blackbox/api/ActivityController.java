package com.blackbox.api;

import com.blackbox.activity.Activity;
import com.blackbox.activity.ActivityStore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/activities")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityStore store;

    @GetMapping
    public List<Activity> getActivities(
            @RequestParam(defaultValue = "6") int limit) {

        return store.getLatest(limit);
    }

    @DeleteMapping
    public void clear() {
        store.clear();
    }
}
