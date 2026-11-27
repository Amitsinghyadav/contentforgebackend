package com.ozo.contentforge.controller;

import com.ozo.contentforge.service.StoryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Team-wide work across all brands. */
@RestController
@RequestMapping("/api/v1/team")
public class TeamController {

    private final StoryService storyService;

    public TeamController(StoryService storyService) {
        this.storyService = storyService;
    }

    /** Story frames with stickers that the team must post by hand in the Instagram app. */
    @GetMapping("/manual-stories")
    public List<Map<String, Object>> manualStories() {
        return storyService.manualQueue();
    }

    @PostMapping("/manual-stories/{frameId}/done")
    public Map<String, Object> markPosted(@PathVariable("frameId") Long frameId) {
        return storyService.markPostedByHand(frameId);
    }
}
