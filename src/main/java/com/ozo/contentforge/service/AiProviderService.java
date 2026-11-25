package com.ozo.contentforge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ozo.contentforge.model.entity.*;
import com.ozo.contentforge.model.enums.*;
import com.ozo.contentforge.repository.BackgroundAssetRepository;
import com.ozo.contentforge.repository.GenerationUsageRepository;
import com.ozo.contentforge.repository.MediaJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class AiProviderService {

    private static final Logger log = LoggerFactory.getLogger(AiProviderService.class);

    @Value("${openai.api.key:}")
    private String openAiKey;

    @Value("${openai.api.model:gpt-4o}")
    private String openAiModel;

    @Value("${openai.api.base-url:https://api.openai.com/v1}")
    private String openAiBaseUrl;

    @Value("${openai.image.model:gpt-image-1.5}")
    private String openAiImageModel;

    @Value("${elevenlabs.api.key:}")
    private String elevenLabsKey;

    @Value("${elevenlabs.api.base-url:https://api.elevenlabs.io/v1}")
    private String elevenLabsBaseUrl;

    @Value("${elevenlabs.voice.model:eleven_multilingual_v2}")
    private String elevenLabsModel;

    @Value("${heygen.api.key:}")
    private String heygenKey;

    @Value("${heygen.api.v3.url:https://api.heygen.com/v3/videos}")
    private String heygenV3Url;

    @Value("${heygen.api.v2.url:https://api.heygen.com/v2/video/generate}")
    private String heygenV2Url;

    @Value("${heygen.api.status-url:https://api.heygen.com/v1/video_status.get}")
    private String heygenStatusUrl;

    @Value("${runway.api.key:}")
    private String runwayKey;

    @Value("${runway.api.url:https://api.dev.runwayml.com/v1/text_to_video}")
    private String runwayUrl;

    @Value("${runway.api.tasks-url:https://api.dev.runwayml.com/v1/tasks}")
    private String runwayTasksUrl;

    @Value("${runway.model:gen4.5}")
    private String runwayModel;

    @Value("${meta.instagram.graph.api.version:v21.0}")
    private String metaGraphVersion;

    @Value("${meta.instagram.graph.api.access-token:}")
    private String metaAccessToken;

    @Value("${meta.instagram.account.id:}")
    private String metaAccountId;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper;
    private final StorageService storageService;
    private final MediaJobRepository mediaJobRepository;
    private final GenerationUsageRepository usageRepository;
    private final BackgroundAssetRepository backgroundRepository;
    private final com.ozo.contentforge.repository.VideoAvatarRepository videoAvatarRepository;
    private final MasterSettingsService masterSettingsService;
    private final MetaIntegrationService metaIntegrationService;

    public AiProviderService(ObjectMapper objectMapper, StorageService storageService,
                             MediaJobRepository mediaJobRepository, GenerationUsageRepository usageRepository,
                             BackgroundAssetRepository backgroundRepository,
                             com.ozo.contentforge.repository.VideoAvatarRepository videoAvatarRepository,
                             MasterSettingsService masterSettingsService,
                             MetaIntegrationService metaIntegrationService) {
        this.objectMapper = objectMapper;
        this.storageService = storageService;
        this.mediaJobRepository = mediaJobRepository;
        this.usageRepository = usageRepository;
        this.backgroundRepository = backgroundRepository;
        this.videoAvatarRepository = videoAvatarRepository;
        this.masterSettingsService = masterSettingsService;
        this.metaIntegrationService = metaIntegrationService;
    }

    private boolean isRealKey(String key) {
        return key != null && !key.trim().isEmpty() && !key.startsWith("YOUR_");
    }

    // =========================================================================
    // 0. AI TOPIC SUGGESTION (DYNAMIC FOR MULTI-BUSINESS B2B PARTNERSHIPS)
    // =========================================================================
    public List<String> generateDailyTopics(String industry, String targetAudience, String brandTone, int count, LocalDate date) {
        if (!isRealKey(openAiKey)) {
            throw new IllegalStateException("OpenAI API key is missing or unconfigured. Please configure 'openai.api.key' in application.properties or Master Settings.");
        }

        try {
            List<String> topics = callOpenAiForTopics(industry, targetAudience, brandTone, count, date);
            if (topics == null || topics.isEmpty()) {
                throw new IllegalStateException("OpenAI returned an empty topic list for industry: " + industry);
            }
            log.info("Successfully generated {} dynamic AI topics from OpenAI for industry '{}' on date {}", topics.size(), industry, date);
            return topics;
        } catch (Exception e) {
            String errorMsg = extractOpenAiErrorMessage(e);
            log.error("OpenAI topic generation failed: {}", errorMsg);
            throw new RuntimeException("AI Topic Suggestion Failed: " + errorMsg, e);
        }
    }

    private List<String> callOpenAiForTopics(String industry, String targetAudience, String brandTone, int count, LocalDate date) throws Exception {
        String effectiveIndustry = (industry != null && !industry.isBlank()) ? industry : "General Business & Lifestyle";
        String effectiveAudience = (targetAudience != null && !targetAudience.isBlank()) ? targetAudience : "Target customers and community followers";
        String effectiveTone = (brandTone != null && !brandTone.isBlank()) ? brandTone : "Engaging, authoritative, relatable, and authentic";

        String prompt = String.format("""
            You are a world-class social media director and creative strategist for %s.
            Industry / Business Domain: %s
            Target Audience: %s
            Brand Voice & Tone: %s
            Target Date: %s
            Number of distinct topics required: %d

            Instructions:
            Generate %d fresh, unique, viral, and high-retention video topics specifically tailored to the '%s' industry.
            Do NOT use generic boilerplate. Tailor each topic to what real people in this market actually ask, search for, or struggle with right now.
            Consider:
            1. Real-world pain points, pressing questions, and high-stakes choices faced by %s.
            2. Trending news, emerging market shifts, or seasonal relevance for %s.
            3. Common misconceptions, practical comparisons, and actionable step-by-step insights in %s.
            4. Varied formats across the list (e.g. myth-busting, insider secrets, beginner traps, comparative breakdown).

            Format each topic as a clear, high-retention headline for an Instagram Reel or Carousel.

            Output ONLY a JSON object in this exact schema without any markdown formatting:
            {
              "topics": [
                "Topic 1...",
                "Topic 2..."
              ]
            }
            """, effectiveIndustry, effectiveIndustry, effectiveAudience, effectiveTone, date, count, count, effectiveIndustry, effectiveAudience, date, effectiveIndustry);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(openAiKey);

        Map<String, Object> body = new HashMap<>();
        body.put("model", openAiModel);
        body.put("messages", List.of(
                Map.of("role", "system", "content", "You are an expert AI social media strategist generating dynamic, industry-tailored topics as valid JSON."),
                Map.of("role", "user", "content", prompt)
        ));
        body.put("response_format", Map.of("type", "json_object"));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(openAiBaseUrl + "/chat/completions", entity, String.class);

        JsonNode root = objectMapper.readTree(response.getBody());
        String content = root.path("choices").get(0).path("message").path("content").asText();
        JsonNode jsonNode = objectMapper.readTree(content);

        List<String> list = new ArrayList<>();
        if (jsonNode.has("topics") && jsonNode.get("topics").isArray()) {
            for (JsonNode t : jsonNode.get("topics")) {
                String str = t.asText().trim();
                if (!str.isEmpty()) list.add(str);
            }
        }
        return list;
    }

    // =========================================================================
    // 1. OPENAI: SCRIPT & STORYBOARD GENERATION
    // =========================================================================
    public static class VideoDimensions {
        public final int width;
        public final int height;
        public final int heygenWidth;
        public final int heygenHeight;
        public final String runwayRatio;
        public final String resolution;
        public final int logoWidth;
        public final int logoX;
        public final int logoY;
        public final int captionMarginV;
        public final int captionFontSize;

        public VideoDimensions(int width, int height, int heygenWidth, int heygenHeight, String runwayRatio,
                               String resolution, int logoWidth, int logoX, int logoY, int captionMarginV, int captionFontSize) {
            this.width = width;
            this.height = height;
            this.heygenWidth = heygenWidth;
            this.heygenHeight = heygenHeight;
            this.runwayRatio = runwayRatio;
            this.resolution = resolution;
            this.logoWidth = logoWidth;
            this.logoX = logoX;
            this.logoY = logoY;
            this.captionMarginV = captionMarginV;
            this.captionFontSize = captionFontSize;
        }

        public static VideoDimensions fromAspectRatio(String ratio) {
            String clean = (ratio != null) ? ratio.trim() : "9:16";
            if ("1:1".equals(clean) || "SQUARE".equalsIgnoreCase(clean)) {
                // 1:1 Square (1080x1080): Instagram Feed Post / Carousel
                return new VideoDimensions(1080, 1080, 1080, 1080, "720:1280", "1080x1080", 150, 35, 35, 100, 50);
            } else if ("16:9".equals(clean) || "LANDSCAPE".equalsIgnoreCase(clean)) {
                // 16:9 Landscape (1920x1080): YouTube / Desktop / TV
                return new VideoDimensions(1920, 1080, 1280, 720, "1280:720", "1920x1080", 200, 50, 50, 120, 54);
            } else {
                // 9:16 Vertical (1080x1920): Instagram Reels / Shorts / TikTok (default)
                return new VideoDimensions(1080, 1920, 720, 1280, "720:1280", "1080x1920", 180, 40, 50, 420, 62);
            }
        }
    }

    public Map<String, Object> generateScriptAndStoryboard(String topic, String industry, String brandTone,
                                                           ContentType contentType, PostMode mode,
                                                           int durationSeconds, String targetAudience) {
        return generateScriptAndStoryboard(topic, industry, brandTone, contentType, mode, durationSeconds, targetAudience, 2, 5, true, null, 6, "9:16");
    }

    public Map<String, Object> generateScriptAndStoryboard(String topic, String industry, String brandTone,
                                                           ContentType contentType, PostMode mode,
                                                           int durationSeconds, String targetAudience,
                                                           int brollCount, int brollClipDuration) {
        return generateScriptAndStoryboard(topic, industry, brandTone, contentType, mode, durationSeconds, targetAudience, brollCount, brollClipDuration, true, null, 6, "9:16");
    }

    public Map<String, Object> generateScriptAndStoryboard(String topic, String industry, String brandTone,
                                                           ContentType contentType, PostMode mode,
                                                           int durationSeconds, String targetAudience,
                                                           int brollCount, int brollClipDuration,
                                                           boolean attachEndCta, String brandCtaText,
                                                           int carouselSlideCount) {
        return generateScriptAndStoryboard(topic, industry, brandTone, contentType, mode, durationSeconds, targetAudience,
                brollCount, brollClipDuration, attachEndCta, brandCtaText, carouselSlideCount, "9:16");
    }

    public Map<String, Object> generateScriptAndStoryboard(String topic, String industry, String brandTone,
                                                           ContentType contentType, PostMode mode,
                                                           int durationSeconds, String targetAudience,
                                                           int brollCount, int brollClipDuration,
                                                           boolean attachEndCta, String brandCtaText,
                                                           int carouselSlideCount, String videoAspectRatio) {
        if (!isRealKey(openAiKey)) {
            throw new IllegalStateException("OpenAI API key is missing or unconfigured. Please configure 'openai.api.key' in application.properties or Master Settings.");
        }

        try {
            return callOpenAiForStoryboard(topic, industry, brandTone, contentType, mode, durationSeconds, targetAudience,
                    brollCount, brollClipDuration, attachEndCta, brandCtaText, carouselSlideCount, videoAspectRatio);
        } catch (Exception e) {
            String errorMsg = extractOpenAiErrorMessage(e);
            log.error("OpenAI script and storyboard generation failed: {}", errorMsg);
            throw new RuntimeException("AI Script Generation Failed: " + errorMsg, e);
        }
    }

    private Map<String, Object> callOpenAiForStoryboard(String topic, String industry, String brandTone,
                                                         ContentType contentType, PostMode mode,
                                                         int durationSeconds, String targetAudience,
                                                         int brollCount, int brollClipDuration,
                                                         boolean attachEndCta, String brandCtaText,
                                                         int carouselSlideCount, String videoAspectRatio) throws Exception {
        String effectiveIndustry = (industry != null && !industry.isBlank()) ? industry : "Business & Lifestyle";
        String effectiveAudience = (targetAudience != null && !targetAudience.isBlank()) ? targetAudience : "Target customers and followers";
        String effectiveTone = (brandTone != null && !brandTone.isBlank()) ? brandTone : "Engaging, authoritative, relatable, and authentic";
        String effectiveCtaText = (brandCtaText != null && !brandCtaText.isBlank()) ? brandCtaText : "Download OZO Health app";
        int targetSlideCount = carouselSlideCount > 0 ? carouselSlideCount : 6;
        String effectiveAspectRatio = (videoAspectRatio != null && !videoAspectRatio.isBlank()) ? videoAspectRatio.trim() : "9:16";
        String ratioDescription = "1:1".equals(effectiveAspectRatio)
                ? "1:1 Square (Instagram Feed Post / Carousel)"
                : "16:9".equals(effectiveAspectRatio)
                ? "16:9 Widescreen Landscape (YouTube / Desktop / TV)"
                : "9:16 Vertical Portrait (Instagram Reels / Shorts / TikTok)";

        int targetReelDuration = durationSeconds > 0 ? durationSeconds : 45;
        int configuredBrollCount = brollCount >= 0 ? brollCount : 2;
        int configuredBrollDuration = brollClipDuration > 0 ? brollClipDuration : 5;

        // Dedicated CTA scene duration (if enabled in settings)
        int ctaDuration = attachEndCta ? Math.min(6, Math.max(4, (int) Math.round(targetReelDuration * 0.25))) : 0;
        int contentDuration = targetReelDuration - ctaDuration;

        // Dynamic B-roll capping: use settings values, but intelligently cap within contentDuration so presenter hook has enough room
        int targetBrollDuration = configuredBrollDuration;
        int targetBrollCount = configuredBrollCount;

        if (targetBrollCount > 0) {
            int maxTotalBrollSeconds = Math.max(0, (int) Math.round(contentDuration * 0.45));
            if (targetBrollDuration > maxTotalBrollSeconds && maxTotalBrollSeconds >= 3) {
                targetBrollDuration = maxTotalBrollSeconds;
            }
            int maxAllowedBrolls = (targetBrollDuration > 0) ? (maxTotalBrollSeconds / targetBrollDuration) : 0;
            if (maxAllowedBrolls < 1 && contentDuration >= 8) {
                maxAllowedBrolls = 1;
                targetBrollDuration = Math.min(targetBrollDuration, Math.max(3, contentDuration / 2));
            }
            targetBrollCount = Math.min(configuredBrollCount, maxAllowedBrolls);
        }

        int totalBrollSeconds = targetBrollCount * targetBrollDuration;
        int totalContentAvatarSeconds = Math.max(4, contentDuration - totalBrollSeconds);
        int contentAvatarCount = (targetBrollCount > 0 && contentDuration > 12) ? (targetBrollCount + 1) : 1;
        int approxContentAvatarDuration = Math.max(3, totalContentAvatarSeconds / contentAvatarCount);

        // Total scenes = content avatar scenes + broll scenes + (1 dedicated CTA scene if enabled)
        int totalScenes = contentAvatarCount + targetBrollCount + (attachEndCta ? 1 : 0);

        // Word budget strictly computed to guarantee audio duration matches targetReelDuration
        // Natural human speech rate in conversational Hinglish is ~2.2 words per second
        int minSpokenWords = Math.max(15, (int) Math.round(targetReelDuration * 2.1));
        int targetSpokenWords = Math.max(18, (int) Math.round(targetReelDuration * 2.25));
        int maxSpokenWords = Math.max(22, (int) Math.round(targetReelDuration * 2.4));

        // Build dynamic scene breakdown and word budget per scene
        StringBuilder timelineBuilder = new StringBuilder();
        int runningTimelineStart = 0;
        int curBrollNum = 1;
        int curAvatarNum = 1;
        int scenesBeforeCta = totalScenes - (attachEndCta ? 1 : 0);

        for (int s = 1; s <= scenesBeforeCta; s++) {
            boolean isBrollScene = (targetBrollCount > 0) && (s % 2 == 0) && (curBrollNum <= targetBrollCount);
            int sceneDur = isBrollScene ? targetBrollDuration : approxContentAvatarDuration;
            if (s == scenesBeforeCta && !attachEndCta) {
                sceneDur = Math.max(3, targetReelDuration - runningTimelineStart);
            } else if (s == scenesBeforeCta) {
                sceneDur = Math.max(3, contentDuration - runningTimelineStart);
            }
            int sceneMinWords = Math.max(4, (int) Math.round(sceneDur * 2.0));
            int sceneTargetWords = Math.max(5, (int) Math.round(sceneDur * 2.25));
            int sceneMaxWords = Math.max(6, (int) Math.round(sceneDur * 2.45));
            String sceneType = isBrollScene ? "BROLL" : "AVATAR";
            String sceneDesc;
            if (s == 1) {
                sceneDesc = "Opening Hook + Problem Setup";
            } else if (isBrollScene) {
                sceneDesc = "Cinematic B-roll Visual Cutaway #" + (curBrollNum++);
            } else {
                sceneDesc = "Core Insight / Solution #" + (curAvatarNum++);
            }

            timelineBuilder.append(String.format("   - Scene %d: %s (%s, duration: exactly %d seconds, word budget: %d-%d words, target: ~%d spoken words)\n",
                    s, sceneType, sceneDesc, sceneDur, sceneMinWords, sceneMaxWords, sceneTargetWords));
            runningTimelineStart += sceneDur;
        }

        if (attachEndCta) {
            int finalCtaDur = Math.max(3, targetReelDuration - runningTimelineStart);
            int ctaMinWords = Math.max(4, (int) Math.round(finalCtaDur * 2.0));
            int ctaTargetWords = Math.max(5, (int) Math.round(finalCtaDur * 2.25));
            int ctaMaxWords = Math.max(6, (int) Math.round(finalCtaDur * 2.45));
            timelineBuilder.append(String.format("   - Scene %d: AVATAR (DEDICATED BRAND CALL-TO-ACTION (CTA) END SCENE, duration: exactly %d seconds, word budget: %d-%d words, target: ~%d spoken words - Presenter explicitly speaks CTA: \"%s\")\n",
                    totalScenes, finalCtaDur, ctaMinWords, ctaMaxWords, ctaTargetWords, effectiveCtaText));
        }
        String timelineStructure = timelineBuilder.toString();

        String ctaReelRules;
        if (attachEndCta) {
            ctaReelRules = String.format("""
               - End Call-To-Action (CTA) is ENABLED in settings.
               - Scene %d MUST be a dedicated Brand Call-To-Action (CTA) ending scene!
               - In Scene %d "spokenText", the presenter MUST explicitly speak this Call-To-Action in conversational Hinglish: "%s" (e.g. "Abhi OZO Health app download karo aur daily preventive insights pao! Follow & share this reel!").
               - The presenterScript MUST include this spoken CTA so ElevenLabs generates it in the voiceover audio track.
               - In Scene %d "visualPrompt", depict the presenter warmly smiling at the camera and pointing to the animated app download / follow badge on screen.
               """, totalScenes, totalScenes, effectiveCtaText, totalScenes);
        } else {
            ctaReelRules = """
               - End Call-To-Action (CTA) is DISABLED in settings.
               - Do NOT add a dedicated CTA scene or promotional pitch at the end.
               - Conclude the reel naturally with the final educational takeaway or insight.
               """;
        }

        String ctaCarouselRules;
        if (attachEndCta) {
            ctaCarouselRules = String.format("""
               - End Call-To-Action (CTA) is ENABLED in settings.
               - Slide %d (the final slide) MUST be a dedicated Brand Call-To-Action (CTA) end slide!
               - Slide %d "title": High-impact action headline (e.g. "Take Action with %s").
               - Slide %d "body": Direct actionable instructions incorporating: "%s". Explicitly tell the user to download the app, save this post for reference, and share with someone who needs it.
               """, targetSlideCount, targetSlideCount, effectiveIndustry, targetSlideCount, effectiveCtaText);
        } else {
            ctaCarouselRules = String.format("""
               - End Call-To-Action (CTA) is DISABLED in settings.
               - Slide %d (the final slide) is a regular summary of key takeaways. Do NOT add a promotional app download card.
               """, targetSlideCount);
        }

        String prompt = String.format("""
            You are a premier Instagram video and content strategist for an automated social media engine.
            Brand industry: %s
            Target audience: %s
            Brand tone: %s
            Content type: %s
            Production mode: %s
            Target Reel Duration: %d seconds
            Video Aspect Ratio: %s (%s)
            Topic: %s

            SCENE COMPOSITION RULES FOR REELS (If Content Type is REEL):
            1. Total Reel duration MUST be exactly %d seconds.
            2. CRITICAL SPOKEN WORD BUDGET & AUDIO DURATION (MANDATORY TIMING REQUIREMENT):
               - TARGET DURATION: Exactly %d seconds. Spoken audio duration in ElevenLabs is directly determined by total spoken words (~2.2 words per second).
               - TOTAL SPOKEN WORDS across all scenes combined MUST BE STRICTLY BETWEEN %d AND %d WORDS (TARGET: ~%d WORDS)!
               - STRICT ENFORCEMENT: DO NOT WRITE FEWER THAN %d WORDS! Writing fewer than %d words WILL CAUSE the audio and video to end prematurely in half the target time (e.g. 23 seconds instead of %d seconds).
               - DO NOT write only 1 short sentence per scene. Each scene's "spokenText" MUST contain substantial, engaging, conversational sentences that reach that scene's designated word budget.
               - Distribute the words across all scenes according to the timeline below.
            3. The reel must contain exactly %d scenes matching this timeline structure:
%s
            4. REEL CALL-TO-ACTION (CTA) RULES:
%s
            5. CRITICAL RULE FOR B-ROLL VISUAL PROMPTS:
               - NEVER output generic placeholders like "capturing key concept of scene 2" or "b-roll related to topic".
               - Write a vivid, cinematic %s (%s) Runway text-to-video prompt detailing what is physically seen on camera (specific subjects, equipment, environment, lighting, camera glide, depth of field, 24fps motion blur) directly depicting the exact concept spoken in that scene!
            6. CRITICAL RULE FOR SPOKEN TEXT - EMOTIONAL DELIVERY & ELEVENLABS VOICE DIRECTION:
               - Every scene (both AVATAR and BROLL) MUST have a specific "spokenText" field.
               - For B-roll scenes, "spokenText" is the voiceover narration spoken while that B-roll is visible.
               - Language: Natural Indian Hinglish (conversational Hindi with natural English terminology).
               - When all "spokenText" fields from Scene 1 to Scene %d are read sequentially, they form one seamless, continuous narration track for the entire %d-second video.
               - Combined word count across all scenes MUST BE BETWEEN %d AND %d WORDS (aiming for %d words)! Never write fewer than %d words.
               - MANDATORY ELEVENLABS VOICE DIRECTION RULES:
                 The spokenText will be fed directly to ElevenLabs TTS. ElevenLabs reads the text and infers
                 emotion from inline audio tags, punctuation, word choice, rhythm, and sentence structure.
                 You MUST write emotionally intelligent, ElevenLabs-ready scripts using these techniques:

                 (a) INLINE EMOTION TAGS - place contextually, not before every sentence:
                   [excited], [surprised], [curious], [sad], [whispers], [laughs], [sighs],
                   [angry], [nervous], [calm], [thoughtful], [serious], [playful], [reassuring]

                 (b) EMOTION-SPECIFIC PATTERNS:
                   SURPRISE/SHOCK: [surprised] "Yaar... sach mein?! Yeh kaise possible hai?!"
                   EXCITEMENT: [excited] "Guys! Yeh toh KAMAAL ho gaya! Seriously, unbelievable!"
                   SAD/HEARTFELT: [sad] "Kabhi kabhi... zindagi mein kuch pal aise aate hain... jo humein andar tak chhoo jaate hain."
                   CURIOSITY/SUSPENSE: [curious] "Lekin... kya aapne kabhi socha hai ki aisa kyun hota hai?"
                   CONFIDENCE: "Aur isi wajah se yeh solution ITNA important hai."
                   EMPATHY: [reassuring] "Main samajh sakta hoon... yeh situation aasaan nahi hai."
                   URGENCY: "Ruko! Yeh galti MAT karna - ABHI dhyan do!"
                   SUSPENSE: [curious] "Kuch toh hai... jo aap nahi jaante. Kuch jo... chhupaaya ja raha hai."
                   HUMOUR: [playful] "Arre bhai... yeh toh ulta hi ho gaya! [laughs]"
                   STORYTELLING: "Ek baar ki baat hai... ek aadmi roz uthta tha, thaka hua. Phir... ek din, sab kuch badal gaya."
                   CTA: [excited] "Toh ABHI app download karo aur apni journey shuru karo - aaj se!"

                 (c) PUNCTUATION & PACING RULES:
                   - "..." for hesitation, anticipation, dramatic pauses
                   - "," for natural breathing pauses
                   - "!" for genuine excitement peaks only - not every sentence
                   - "?" for questioning intonation
                   - "---" for abrupt dramatic shifts
                   - ONE word in ALL-CAPS per sentence for vocal emphasis
                   - Short 3-5 word sentences = high energy; Long comma-separated = slow/emotional

                 (d) EMOTIONAL TRANSITIONS:
                   - Change emotions naturally as the story progresses
                   - Use suspense before reveals, excitement during discoveries, calm during explanations
                   - Do NOT put an emotion tag before every sentence - only at meaningful transitions
                   - The final audio must sound like a real human speaking spontaneously, not a robot

            CAROUSEL COMPOSITION RULES (If Content Type is CAROUSEL):
            1. Generate exactly %d slides in the "slides" array.
            2. CAROUSEL CALL-TO-ACTION (CTA) RULES:
%s

            Generate a complete structured JSON with:
            {
              "contentType": "%s",
              "targetDurationSeconds": %d,
              "language": "HINGLISH",
              "hook": "Compelling 1-3 second attention hook specifically about %s",
              "presenterScript": "Full spoken presenter script (combined text of all AVATAR scenes)",
              "voiceoverScript": "Full spoken voiceover narration (combined text of all BROLL scenes)",
              "caption": "Engaging Instagram caption with bullet points and emojis tailored to %s",
              "hashtags": "5 to 8 relevant hashtags matching %s and %s",
              "cta": "%s",
              "clinicalReviewRequired": false,
              "scenes": [
                 {
                   "sceneNumber": 1,
                   "type": "AVATAR",
                   "startSeconds": 0,
                   "durationSeconds": %d,
                   "aspectRatio": "%s",
                   "spokenText": "Yaar, kya aap JAANTE hain?! Ek aisi baat jo aapki zindagi badal sakti hai... [Emotionally charged Hinglish — use surprise, rhetorical questions, ALL-CAPS for emphasis, ellipsis for dramatic pauses, ! for excitement per scene emotion]",
                   "visualPrompt": "Confident presenter looking directly into the camera in a modern studio with soft warm rim lighting and shallow depth of field",
                   "cameraDirection": "Slow gentle push-in"
                 }
              ],
              "slides": [
                 {
                   "slideNumber": 1,
                   "title": "Title for Slide 1",
                   "body": "Punchy educational description for slide 1"
                 }
              ]
            }
            Ensure the "scenes" array contains exactly all %d scenes matching the timeline structure above, with exact startSeconds and durationSeconds summing to %d.
            Ensure the "slides" array contains exactly %d slides.
            Output only raw valid JSON without markdown fences.
            """,
            effectiveIndustry, effectiveAudience, effectiveTone, contentType, mode, targetReelDuration, effectiveAspectRatio, ratioDescription, topic,
            targetReelDuration,
            targetReelDuration, minSpokenWords, maxSpokenWords, targetSpokenWords, minSpokenWords, minSpokenWords, targetReelDuration,
            totalScenes,
            timelineStructure,
            ctaReelRules,
            effectiveAspectRatio, ratioDescription,
            totalScenes, targetReelDuration,
            minSpokenWords, maxSpokenWords, targetSpokenWords, minSpokenWords,
            targetSlideCount,
            ctaCarouselRules,
            contentType, targetReelDuration, topic, topic, topic, effectiveIndustry, effectiveCtaText,
            approxContentAvatarDuration, effectiveAspectRatio,
            totalScenes, targetReelDuration,
            targetSlideCount);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(openAiKey);

        Map<String, Object> body = new HashMap<>();
        body.put("model", openAiModel);
        body.put("messages", List.of(
                Map.of("role", "system", "content", "You are an expert AI social video and script generator returning structured JSON."),
                Map.of("role", "user", "content", prompt)
        ));
        body.put("response_format", Map.of("type", "json_object"));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(openAiBaseUrl + "/chat/completions", entity, String.class);

        JsonNode root = objectMapper.readTree(response.getBody());
        String content = root.path("choices").get(0).path("message").path("content").asText();
        return objectMapper.readValue(content, new TypeReference<Map<String, Object>>() {});
    }

    public String extractOpenAiErrorMessage(Exception e) {
        if (e instanceof org.springframework.web.client.HttpStatusCodeException httpEx) {
            String responseBody = httpEx.getResponseBodyAsString();
            try {
                JsonNode root = objectMapper.readTree(responseBody);
                if (root.has("error") && root.get("error").has("message")) {
                    String msg = root.get("error").get("message").asText();
                    String code = root.get("error").has("code") ? root.get("error").get("code").asText() : "";
                    String type = root.get("error").has("type") ? root.get("error").get("type").asText() : "";
                    return String.format("OpenAI API error %d [%s%s]: %s",
                            httpEx.getStatusCode().value(),
                            type != null && !type.isBlank() ? type : "error",
                            code != null && !code.isBlank() ? " / " + code : "",
                            msg);
                }
            } catch (Exception ignored) {}
            return String.format("OpenAI API returned HTTP %d %s: %s",
                    httpEx.getStatusCode().value(), httpEx.getStatusText(), responseBody);
        }
        return e.getMessage() != null ? e.getMessage() : "Unknown OpenAI connection error";
    }

    // =========================================================================
    // 1B. OPENAI DALL-E / IMAGE GENERATION FOR SINGLE POSTS
    // =========================================================================
    // 1. OPENAI: DYNAMIC AI IMAGE GENERATION (POSTS & CAROUSEL SLIDES)
    // =========================================================================
    public GeneratedAssetEntity generatePostImage(Long postId, String prompt, String topic, String industry) throws Exception {
        log.info("Generating AI Image Post visual for post {} (topic: '{}')...", postId, topic);
        String cleanTopic = (topic != null && !topic.isBlank()) ? topic : "Health & Wellness Guidance";
        String cleanIndustry = (industry != null && !industry.isBlank()) ? industry : "Healthcare";
        String effectivePrompt = (prompt != null && !prompt.isBlank())
                ? String.format("A high-impact social media graphic poster for %s about '%s'. %s. Clean, bold, legible typography, compelling modern visual aesthetics, 1024x1024 square.", cleanIndustry, cleanTopic, prompt)
                : String.format("A high-impact social media graphic poster for %s on the topic '%s'. Prominently render bold, stylish typography with headline text: \"%s\". Combine with professional, elegant medical visuals and modern graphic design layout, 1024x1024 square.", cleanIndustry, cleanTopic, cleanTopic);

        return callOpenAiImageGeneration(postId, effectivePrompt, "GENERATE_POST_IMAGE");
    }

    public GeneratedAssetEntity generateCarouselSlideImage(Long postId, int slideNumber, String title, String bodyContent, String topic, String industry) throws Exception {
        return generateCarouselSlideImage(postId, slideNumber, title, bodyContent, topic, industry, false);
    }

    public GeneratedAssetEntity generateCarouselSlideImage(Long postId, int slideNumber, String title, String bodyContent, String topic, String industry, boolean isCtaSlide) throws Exception {
        log.info("Generating AI Carousel Slide {} image for post {} (title: '{}', isCtaSlide: {})...", slideNumber, postId, title, isCtaSlide);
        String cleanTopic = (topic != null && !topic.isBlank()) ? topic : "Healthcare";
        String cleanIndustry = (industry != null && !industry.isBlank()) ? industry : "Healthcare";
        String cleanTitle = (title != null && !title.isBlank()) ? title : ("Slide " + slideNumber);
        String cleanBody = (bodyContent != null && !bodyContent.isBlank()) ? bodyContent : cleanTopic;

        String effectivePrompt;
        if (isCtaSlide) {
            effectivePrompt = String.format(
                    "A high-impact concluding Call-To-Action (CTA) presentation slide for social media carousel for %s. " +
                    "Prominently display clean, bold modern typography featuring the main action headline: \"%s\". " +
                    "Include distinct, inspiring call-to-action text: \"%s\". " +
                    "Feature sleek modern action iconography such as mobile app store badge, save bookmark icon, and follow/share symbols. " +
                    "Professional branded presentation slide layout, 1024x1024 square, clean negative space, elegant color harmony.",
                    cleanIndustry, cleanTitle, cleanBody
            );
        } else {
            effectivePrompt = String.format(
                    "A professionally designed social media carousel presentation card for %s about '%s'. " +
                    "Prominently render bold, crisp typography displaying the slide title: \"%s\". " +
                    "Include clean, legible subtitle text: \"%s\". " +
                    "Incorporate modern, high-quality medical/scientific 3D illustrations or iconography matching the topic. " +
                    "Well-balanced graphic poster layout with polished typography, beautiful color harmony, 1024x1024 square.",
                    cleanIndustry, cleanTopic, cleanTitle, cleanBody
            );
        }

        return callOpenAiImageGeneration(postId, effectivePrompt, "GENERATE_CAROUSEL_SLIDE_" + slideNumber);
    }

    private GeneratedAssetEntity callOpenAiImageGeneration(Long postId, String effectivePrompt, String jobOperation) throws Exception {
        if (!isRealKey(openAiKey)) {
            throw new IllegalStateException("OpenAI API key is missing or unconfigured. Cannot generate AI image.");
        }
        MediaJobEntity job = recordJob(postId, null, JobProvider.OPENAI, jobOperation);

        String[] modelsToTry = new String[] {
                (openAiImageModel != null && !openAiImageModel.isBlank()) ? openAiImageModel : "gpt-image-1.5",
                "gpt-image-1-mini",
                "chatgpt-image-latest"
        };

        Exception lastException = null;

        for (String model : modelsToTry) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(openAiKey);

                Map<String, Object> body = new HashMap<>();
                body.put("model", model);
                body.put("prompt", effectivePrompt);
                body.put("n", 1);
                body.put("size", "1024x1024");

                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
                ResponseEntity<String> response = restTemplate.postForEntity("https://api.openai.com/v1/images/generations", entity, String.class);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    JsonNode root = objectMapper.readTree(response.getBody());
                    if (root.has("data") && root.get("data").isArray() && root.get("data").size() > 0) {
                        JsonNode firstItem = root.get("data").get(0);
                        byte[] imgBytes = null;

                        if (firstItem.has("b64_json") && !firstItem.get("b64_json").asText().isBlank()) {
                            imgBytes = java.util.Base64.getDecoder().decode(firstItem.get("b64_json").asText());
                        } else if (firstItem.has("url") && !firstItem.get("url").asText().isBlank()) {
                            String imageUrl = firstItem.get("url").asText();
                            imgBytes = restTemplate.getForObject(imageUrl, byte[].class);
                        }

                        if (imgBytes != null && imgBytes.length > 0) {
                            byte[] brandedImgBytes = overlayBrandLogoIfConfigured(imgBytes);

                            GeneratedAssetEntity asset = storageService.saveGeneratedMedia(
                                    brandedImgBytes,
                                    ".png",
                                    "image/png",
                                    AssetType.IMAGE,
                                    postId,
                                    null,
                                    null,
                                    String.format("{\"prompt\": %s, \"model\": \"%s\"}", objectMapper.writeValueAsString(effectivePrompt), model)
                            );
                            job.setStatus(JobStatus.COMPLETED);
                            job.setCostUsd(0.04);
                            job.setCompletedAt(LocalDateTime.now());
                            mediaJobRepository.save(job);
                            log.info("Successfully generated AI image for post {} using model {}: {}", postId, model, asset.getStorageUrl());
                            return asset;
                        }
                    }
                }
            } catch (Exception e) {
                lastException = e;
                log.warn("OpenAI image generation failed with model {}: {}. Trying next model...", model, e.getMessage());
            }
        }

        job.setStatus(JobStatus.FAILED);
        String errDetail = lastException != null ? lastException.getMessage() : "All OpenAI image generation models failed";
        job.setErrorJson(String.format("{\"error\": %s}", objectMapper.writeValueAsString(errDetail)));
        mediaJobRepository.save(job);
        log.error("AI image generation completely failed for post {}: {}", postId, errDetail);
        throw new RuntimeException("AI Image Generation Failed: " + errDetail);
    }

    /**
     * Overlay brand logo onto generated image (e.g. Carousel slide or Image post).
     * Automatically adjusts scale, positioning, and adds a sleek translucent backdrop for high contrast.
     */
    private byte[] overlayBrandLogoIfConfigured(byte[] baseImageBytes) {
        if (masterSettingsService == null) {
            return baseImageBytes;
        }
        try {
            Map<String, Object> settings = masterSettingsService.getAllSettings();
            String logoUrl = (String) settings.get("brandLogoUrl");
            if (logoUrl == null || logoUrl.isBlank()) {
                return baseImageBytes;
            }

            byte[] logoBytes = null;
            String cleanKey = storageService.extractS3Key(logoUrl);
            if (cleanKey != null && !cleanKey.startsWith("http")) {
                logoBytes = storageService.getS3Object(cleanKey);
            } else if (logoUrl.startsWith("http")) {
                logoBytes = restTemplate.getForObject(logoUrl, byte[].class);
            }

            if (logoBytes == null || logoBytes.length == 0) {
                return baseImageBytes;
            }

            BufferedImage baseImg = ImageIO.read(new ByteArrayInputStream(baseImageBytes));
            BufferedImage logoImg = ImageIO.read(new ByteArrayInputStream(logoBytes));
            if (baseImg == null || logoImg == null) {
                return baseImageBytes;
            }

            int baseW = baseImg.getWidth();
            int baseH = baseImg.getHeight();

            // Mathematically maintain 100% exact original aspect ratio with zero distortion or squishing
            int maxW = (int) (baseW * 0.28); // ~286px on 1024x1024 (ensures text remains sharp and legible)
            int maxH = (int) (baseH * 0.12); // ~120px on 1024x1024

            double scale = Math.min((double) maxW / logoImg.getWidth(), (double) maxH / logoImg.getHeight());
            if (scale > 1.0) {
                scale = 1.0; // Keep 1:1 original if logo is already compact
            }

            int targetLogoW = (int) Math.round(logoImg.getWidth() * scale);
            int targetLogoH = (int) Math.round(logoImg.getHeight() * scale);

            int margin = Math.max(24, (int) (baseW * 0.03));
            int posY = margin;

            // Pick the empty corner (Top-Right or Top-Left) based on open negative space
            int trX = baseW - targetLogoW - margin;
            int tlX = margin;

            double trComplexity = calculateRegionComplexity(baseImg, trX, posY, targetLogoW, targetLogoH);
            double tlComplexity = calculateRegionComplexity(baseImg, tlX, posY, targetLogoW, targetLogoH);

            int posX = (trComplexity <= tlComplexity * 1.25) ? trX : tlX;

            BufferedImage combined = new BufferedImage(baseW, baseH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = combined.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);

            // 1. Draw poster
            g.drawImage(baseImg, 0, 0, null);

            // 2. Paste logo directly as-is into open space with exact original aspect ratio
            g.drawImage(logoImg, posX, posY, targetLogoW, targetLogoH, null);
            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(combined, "png", baos);
            log.info("Pasted transparent brand logo into open {} corner as-is", (posX == trX ? "Top-Right" : "Top-Left"));
            return baos.toByteArray();
        } catch (Exception e) {
            log.warn("Brand logo placement skipped: {}", e.getMessage());
            return baseImageBytes;
        }
    }

    /**
     * Compute visual complexity score in a region to find the empty space corner.
     */
    private double calculateRegionComplexity(BufferedImage img, int rx, int ry, int rw, int rh) {
        if (img == null) return 0;
        int imgW = img.getWidth();
        int imgH = img.getHeight();

        long totalLum = 0;
        int count = 0;
        int step = 2;

        int maxX = Math.min(rx + rw, imgW);
        int maxY = Math.min(ry + rh, imgH);

        for (int y = Math.max(0, ry); y < maxY; y += step) {
            for (int x = Math.max(0, rx); x < maxX; x += step) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                int lum = (299 * r + 587 * g + 114 * b) / 1000;
                totalLum += lum;
                count++;
            }
        }

        if (count == 0) return 0;
        double mean = (double) totalLum / count;
        double variance = 0;

        for (int y = Math.max(0, ry); y < maxY; y += step) {
            for (int x = Math.max(0, rx); x < maxX; x += step) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                int lum = (299 * r + 587 * g + 114 * b) / 1000;
                variance += (lum - mean) * (lum - mean);
            }
        }

        return variance / count;
    }

    // =========================================================================
    // 2. ELEVENLABS: VOICE CLONING & TTS
    // =========================================================================
    public GeneratedAssetEntity generateVoiceAudio(Long postId, String text, String voiceId) throws Exception {
        log.info("Generating ElevenLabs voice audio for post {}...", postId);
        MediaJobEntity job = recordJob(postId, null, JobProvider.ELEVENLABS, "GENERATE_VOICE");

        int charCount = text != null ? text.length() : 300;
        double cost = charCount * 0.0003;

        String effectiveVoiceId = (voiceId != null && !voiceId.isBlank() && !voiceId.startsWith("el_"))
                ? voiceId
                : "rRAzMEZ6U57ivzmuPBhL"; // Default Amit Singh Yadav cloned voice

        if (isRealKey(elevenLabsKey)) {
            // Apply GPT voice direction pass: transforms raw spokenText into ElevenLabs-ready
            // emotionally tagged script with [excited], [surprised], [sad] etc. inline tags.
            String directedText = applyVoiceDirection(text);
            log.info("Voice direction applied. Original chars: {}, Directed chars: {}", text != null ? text.length() : 0, directedText.length());
            byte[] audioBytes = callElevenLabsTts(directedText, effectiveVoiceId);
            if (audioBytes == null && !"rRAzMEZ6U57ivzmuPBhL".equals(effectiveVoiceId)) {
                log.warn("ElevenLabs TTS failed for voice '{}'. Retrying with verified default voice 'rRAzMEZ6U57ivzmuPBhL' (Amit Singh Yadav)...", effectiveVoiceId);
                audioBytes = callElevenLabsTts(text, "rRAzMEZ6U57ivzmuPBhL");
            }

            if (audioBytes != null && audioBytes.length > 500) {
                completeJob(job, cost);
                recordUsage(postId, "ELEVENLABS", elevenLabsModel, (double) charCount, "CHARACTERS", cost);
                return storageService.saveGeneratedMedia(audioBytes, ".mp3", "audio/mpeg", AssetType.AUDIO, postId, null, 45.0, "{\"provider\": \"ElevenLabs\"}");
            }
        }

        job.setStatus(JobStatus.FAILED);
        job.setErrorJson("ElevenLabs TTS failed to generate audio for voice ID: " + voiceId);
        mediaJobRepository.save(job);
        throw new IllegalStateException("ElevenLabs TTS failed to generate valid audio for voice ID: " + voiceId);
    }

    /**
     * GPT-powered Voice Direction Pass.
     * Transforms raw spokenText into an ElevenLabs-ready, emotionally expressive script.
     * Adds inline emotion tags ([excited], [surprised], [sad], etc.), natural punctuation,
     * pacing cues, and conversational rhythm so ElevenLabs renders genuine emotion.
     * Falls back to the original text if GPT is unavailable or fails.
     */
    private String applyVoiceDirection(String text) {
        if (text == null || text.isBlank()) return text;
        if (!isRealKey(openAiKey)) {
            log.debug("OpenAI key not configured - skipping voice direction pass, using raw text.");
            return text;
        }
        try {
            String systemPrompt = """
You are an expert AI voice director, emotional storytelling specialist, and script optimizer for ElevenLabs Text-to-Speech.
Your task is to transform any script into natural, emotionally expressive, human-like speech using ElevenLabs-compatible audio tags, punctuation, pacing, and conversational rhythm.

CORE RULES:

1. EMOTIONAL INTELLIGENCE
- Analyze the meaning, context, and emotional intention of every sentence.
- Automatically identify emotions: excitement, surprise, curiosity, suspense, sadness, empathy, happiness, confidence, frustration, fear, reassurance, and calmness.
- Apply appropriate emotion tags only when they improve delivery.
- Preserve the original meaning, language, and factual content.

2. ELEVENLABS AUDIO TAGS
Use these inline audio tags contextually - never before every sentence:
[excited], [surprised], [curious], [sad], [whispers], [laughs], [sighs], [angry], [nervous], [calm], [thoughtful], [serious], [playful], [reassuring]
Place tags only at meaningful emotional transitions. Some tags are voice-dependent.

3. EMOTIONAL DELIVERY PATTERNS
SURPRISE/SHOCK: [surprised] "Yaar... sach mein?! Yeh kaise possible hai?!"
EXCITEMENT: [excited] "Guys! Yeh toh KAMAAL ho gaya! Seriously, unbelievable!"
SAD/HEARTFELT: [sad] "Kabhi kabhi... zindagi mein kuch pal aise aate hain... jo humein andar tak chhoo jaate hain."
CURIOSITY/SUSPENSE: [curious] "Lekin... kya aapne kabhi socha hai ki aisa kyun hota hai?"
CONFIDENCE: "Aur isi wajah se yeh solution ITNA important hai."
EMPATHY: [reassuring] "Main samajh sakta hoon... yeh situation aasaan nahi hai."
URGENCY: "Ruko! Yeh galti MAT karna - ABHI dhyan do!"
SUSPENSE: [curious] "Kuch toh hai... jo aap nahi jaante. Kuch jo... chhupaaya ja raha hai."
HUMOUR: [playful] "Arre bhai... yeh toh ulta hi ho gaya! [laughs]"
CTA: [excited] "Toh ABHI app download karo aur apni journey shuru karo - aaj se!"

4. PUNCTUATION AND PACING
- Use "..." for hesitation, anticipation, dramatic pauses.
- Use "," for natural breathing pauses.
- Use "!" for genuine excitement peaks only - not every sentence.
- Use "?" for questioning intonation.
- Use "---" for abrupt dramatic shifts.
- Use ALL-CAPS sparingly - ONE key word per sentence for vocal emphasis.
- Short 3-5 word sentences = high energy. Long comma-separated sentences = slow/emotional.

5. HINDI/ENGLISH/HINGLISH
- Preserve the original language and natural code-switching.
- Maintain authentic Indian conversational rhythm.
- Avoid robotic translations. Write natural, speakable sentences.

6. EMOTIONAL TRANSITIONS
- Change emotions naturally as the story progresses.
- Use suspense before reveals, excitement during discoveries, calm during explanations.
- Do NOT add emotion tags before every sentence.

7. VOICE NATURALNESS
- Final audio must sound like a real human speaking spontaneously.
- Avoid overacting, exaggerated shouting, artificial pauses.
- Prefer subtle, believable emotional delivery.
- Use [laughs] or [sighs] only when contextually appropriate.

8. OUTPUT RULES
- Output ONLY the final ElevenLabs-ready script text.
- Include emotional audio tags inline with the spoken text.
- Do NOT include explanations, headings, timestamps, markdown, or commentary.
- Do NOT alter essential facts, names, numbers, or intended message.
- Do NOT include unsupported production instructions as spoken text.
""";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openAiKey);

            Map<String, Object> body = new HashMap<>();
            body.put("model", openAiModel);
            body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content",
                    "Transform this script into an emotionally expressive ElevenLabs-ready script. " +
                    "Output ONLY the final script text with inline emotion tags. No explanations.\\n\\nSCRIPT:\\n" + text)
            ));
            body.put("max_tokens", 2000);
            body.put("temperature", 0.7);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(
                openAiBaseUrl + "/chat/completions", entity, String.class
            );

            JsonNode root = objectMapper.readTree(response.getBody());
            String directed = root.path("choices").get(0).path("message").path("content").asText().trim();
            if (directed.isBlank()) {
                log.warn("Voice direction returned empty result - falling back to original text.");
                return text;
            }
            return directed;
        } catch (Exception e) {
            log.warn("Voice direction GPT pass failed: {} - falling back to original text.", e.getMessage());
            return text; // Graceful fallback - never break TTS pipeline
        }
    }

    private byte[] callElevenLabsTts(String text, String voiceId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("xi-api-key", elevenLabsKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            // PVC (Professional Voice Clone) voices need slightly higher stability to stay on-voice.
            // For standard cloned/library voices we want:
            //   stability LOW  â†’ more natural pitch variation & emotion (not robotic)
            //   style HIGH     â†’ style/expressiveness exaggeration (excitement, surprise, warmth)
            //   similarity LOW-MED â†’ allows the voice to "act" rather than just mimic baseline recording
            //   use_speaker_boost â†’ enhances clarity and presence
            boolean isPvc = (voiceId != null && (voiceId.startsWith("el_pvc_") || voiceId.contains("pvc")));
            double stability       = isPvc ? 0.38 : 0.28;   // Lower = more dynamic, more emotion
            double similarity      = isPvc ? 0.88 : 0.78;   // Mid = lets voice emote freely
            double style           = isPvc ? 0.55 : 0.65;   // Higher = more expressive / style exaggeration
            double speakerBoost    = 1.0;                    // always on

            Map<String, Object> body = Map.of(
                    "text", text,
                    "model_id", elevenLabsModel,
                    "voice_settings", Map.of(
                            "stability", stability,
                            "similarity_boost", similarity,
                            "style", style,
                            "use_speaker_boost", true
                    )
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<byte[]> response = restTemplate.postForEntity(
                    elevenLabsBaseUrl + "/text-to-speech/" + voiceId, entity, byte[].class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null && response.getBody().length > 500) {
                return response.getBody();
            }
        } catch (Exception e) {
            log.warn("ElevenLabs TTS call failed for voiceId '{}': {}", voiceId, e.getMessage());
        }
        return null;
    }

    public String cloneElevenLabsVoice(String name, byte[] audioBytes, String filename, String description) {
        if (!isRealKey(elevenLabsKey)) {
            log.warn("ElevenLabs API key is unconfigured. Generating synthetic cloned voice ID.");
            return "el_voice_" + UUID.randomUUID().toString().substring(0, 8);
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("xi-api-key", elevenLabsKey);
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            org.springframework.util.MultiValueMap<String, Object> form = new org.springframework.util.LinkedMultiValueMap<>();
            form.add("name", (name != null && !name.isBlank()) ? name : "Cloned Voice " + UUID.randomUUID().toString().substring(0, 4));
            if (description != null && !description.isBlank()) {
                form.add("description", description);
            }

            org.springframework.core.io.ByteArrayResource fileResource = new org.springframework.core.io.ByteArrayResource(audioBytes) {
                @Override
                public String getFilename() {
                    return filename != null ? filename : "sample.mp3";
                }
            };
            form.add("files", fileResource);

            HttpEntity<org.springframework.util.MultiValueMap<String, Object>> entity = new HttpEntity<>(form, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(elevenLabsBaseUrl + "/voices/add", entity, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            if (root.has("voice_id")) {
                String vid = root.get("voice_id").asText();
                log.info("ElevenLabs voice created successfully with ID: {}", vid);
                return vid;
            }
            throw new RuntimeException("ElevenLabs did not return a voice_id: " + response.getBody());
        } catch (Exception e) {
            String errorMsg = extractElevenLabsErrorMessage(e);
            log.error("ElevenLabs voice cloning failed: {}", errorMsg);
            throw new RuntimeException("ElevenLabs Voice Cloning Failed: " + errorMsg, e);
        }
    }

    public String cloneElevenLabsPvcVoice(String name, byte[] audioBytes, String filename, String description,
                                          byte[] consentBytes, String consentFilename) {
        log.info("Initiating ElevenLabs Professional Voice Clone (PVC) for '{}' (sample size: {} bytes)...",
                name, audioBytes != null ? audioBytes.length : 0);
        if (!isRealKey(elevenLabsKey)) {
            throw new IllegalStateException("ElevenLabs API key is unconfigured. Please configure 'elevenlabs.api.key' in Master Settings.");
        }

        try {
            // STEP 1: Create PVC Container via POST /v1/voices/pvc
            HttpHeaders jsonHeaders = new HttpHeaders();
            jsonHeaders.set("xi-api-key", elevenLabsKey);
            jsonHeaders.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> createPayload = new HashMap<>();
            createPayload.put("name", (name != null && !name.isBlank()) ? name : "PVC Voice " + UUID.randomUUID().toString().substring(0, 4));
            createPayload.put("language", "en");
            createPayload.put("description", (description != null && !description.isBlank()) 
                    ? description 
                    : "High-fidelity professional voice clone trained via ContentForge");
            createPayload.put("labels", Map.of(
                    "tier", "professional",
                    "origin", "contentforge"
            ));

            HttpEntity<Map<String, Object>> createEntity = new HttpEntity<>(createPayload, jsonHeaders);
            log.info("Calling ElevenLabs PVC creation endpoint: {}/voices/pvc...", elevenLabsBaseUrl);
            ResponseEntity<String> createResp = restTemplate.postForEntity(elevenLabsBaseUrl + "/voices/pvc", createEntity, String.class);
            JsonNode createRoot = objectMapper.readTree(createResp.getBody());
            if (!createRoot.has("voice_id")) {
                throw new RuntimeException("ElevenLabs did not return a voice_id for PVC container: " + createResp.getBody());
            }
            String voiceId = createRoot.get("voice_id").asText();
            log.info("ElevenLabs PVC container created with voice_id: {}", voiceId);

            // STEP 2: Upload Samples to PVC container via POST /v1/voices/pvc/{voice_id}/samples
            if (audioBytes != null && audioBytes.length > 0) {
                HttpHeaders formHeaders = new HttpHeaders();
                formHeaders.set("xi-api-key", elevenLabsKey);
                formHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

                org.springframework.util.MultiValueMap<String, Object> form = new org.springframework.util.LinkedMultiValueMap<>();
                org.springframework.core.io.ByteArrayResource fileResource = new org.springframework.core.io.ByteArrayResource(audioBytes) {
                    @Override
                    public String getFilename() {
                        return filename != null ? filename : "studio_sample.mp3";
                    }
                };
                form.add("files", fileResource);

                if (consentBytes != null && consentBytes.length > 0) {
                    org.springframework.core.io.ByteArrayResource consentResource = new org.springframework.core.io.ByteArrayResource(consentBytes) {
                        @Override
                        public String getFilename() {
                            return consentFilename != null ? consentFilename : "pvc_consent.mp3";
                        }
                    };
                    form.add("files", consentResource);
                }
                form.add("remove_background_noise", "false");

                HttpEntity<org.springframework.util.MultiValueMap<String, Object>> sampleEntity = new HttpEntity<>(form, formHeaders);
                log.info("Uploading audio samples to ElevenLabs PVC endpoint: {}/voices/pvc/{}/samples...", elevenLabsBaseUrl, voiceId);
                restTemplate.postForEntity(elevenLabsBaseUrl + "/voices/pvc/" + voiceId + "/samples", sampleEntity, String.class);
                log.info("Uploaded PVC audio samples successfully for voice_id: {}", voiceId);
            }

            // STEP 3: Trigger Model Training via POST /v1/voices/pvc/{voice_id}/train
            Map<String, Object> trainPayload = new HashMap<>();
            if (elevenLabsModel != null && !elevenLabsModel.isBlank()) {
                trainPayload.put("model_id", elevenLabsModel);
            }
            HttpEntity<Map<String, Object>> trainEntity = new HttpEntity<>(trainPayload, jsonHeaders);
            log.info("Triggering ElevenLabs PVC training for voice_id: {} with model: {}...", voiceId, elevenLabsModel);
            try {
                restTemplate.postForEntity(elevenLabsBaseUrl + "/voices/pvc/" + voiceId + "/train", trainEntity, String.class);
                log.info("Successfully queued ElevenLabs PVC model training for voice_id: {}", voiceId);
            } catch (Exception trainEx) {
                log.warn("ElevenLabs PVC train call returned warning: {}. The container and samples are created with voice_id: {}",
                        trainEx.getMessage(), voiceId);
            }

            return voiceId;
        } catch (Exception e) {
            String errorMsg = extractElevenLabsErrorMessage(e);
            log.error("ElevenLabs PVC training failed: {}", errorMsg);
            throw new RuntimeException("ElevenLabs Professional Voice Cloning Failed: " + errorMsg, e);
        }
    }

    public Map<String, Object> getElevenLabsVoiceDetails(String voiceId) {
        if (!isRealKey(elevenLabsKey) || voiceId == null || voiceId.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("xi-api-key", elevenLabsKey);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    elevenLabsBaseUrl + "/voices/" + voiceId, org.springframework.http.HttpMethod.GET, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                Map<String, Object> map = new HashMap<>();
                map.put("voice_id", root.path("voice_id").asText(voiceId));
                map.put("name", root.path("name").asText());
                map.put("category", root.path("category").asText());
                if (root.has("fine_tuning")) {
                    JsonNode ft = root.get("fine_tuning");
                    JsonNode stateNode = ft.path("state");
                    String fineTuningState = "unknown";
                    if (stateNode.isObject() && stateNode.size() > 0) {
                        fineTuningState = stateNode.fields().next().getValue().asText();
                    } else if (stateNode.isTextual()) {
                        fineTuningState = stateNode.asText();
                    }
                    map.put("fineTuningState", fineTuningState);
                    map.put("isAllowedToFineTune", ft.path("is_allowed_to_fine_tune").asBoolean(true));
                }
                return map;
            }
        } catch (Exception e) {
            log.warn("Failed to fetch ElevenLabs voice details for '{}': {}", voiceId, e.getMessage());
        }
        return Collections.emptyMap();
    }

    public String extractElevenLabsErrorMessage(Exception e) {
        if (e instanceof org.springframework.web.client.HttpStatusCodeException httpEx) {
            String body = httpEx.getResponseBodyAsString();
            try {
                JsonNode root = objectMapper.readTree(body);
                if (root.has("detail")) {
                    JsonNode detail = root.get("detail");
                    if (detail.isObject() && detail.has("message")) {
                        return detail.get("message").asText();
                    } else if (detail.isTextual()) {
                        return detail.asText();
                    }
                }
                if (root.has("message")) {
                    return root.get("message").asText();
                }
            } catch (Exception ignored) {}
            return "HTTP " + httpEx.getStatusCode().value() + ": " + body;
        }
        return e.getMessage() != null ? e.getMessage() : "Unknown ElevenLabs error";
    }

    public String uploadHeyGenTalkingPhoto(byte[] photoBytes, String contentType, String filename) {
        if (!isRealKey(heygenKey)) {
            log.warn("HeyGen API key is unconfigured. Generating synthetic talking photo ID.");
            return "talking_photo_" + UUID.randomUUID().toString().substring(0, 8);
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Api-Key", heygenKey);
            headers.setContentType(MediaType.parseMediaType(contentType != null ? contentType : "image/jpeg"));

            HttpEntity<byte[]> requestEntity = new HttpEntity<>(photoBytes, headers);
            ResponseEntity<String> response = restTemplate.postForEntity("https://upload.heygen.com/v1/talking_photo", requestEntity, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            if (root.has("data") && root.get("data").has("talking_photo_id")) {
                return root.get("data").get("talking_photo_id").asText();
            } else if (root.has("data") && root.get("data").has("id")) {
                return root.get("data").get("id").asText();
            }
        } catch (Exception e) {
            log.warn("HeyGen talking photo upload failed: {}. Using generated ID.", e.getMessage());
        }
        return "talking_photo_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String uploadHeyGenVideoAsset(byte[] videoBytes, String contentType, String filename) {
        if (!isRealKey(heygenKey)) {
            log.warn("HeyGen API key is unconfigured. Generating synthetic asset ID.");
            return "asset_" + UUID.randomUUID().toString().substring(0, 8);
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Api-Key", heygenKey);
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            org.springframework.util.MultiValueMap<String, Object> form = new org.springframework.util.LinkedMultiValueMap<>();
            org.springframework.core.io.ByteArrayResource fileResource = new org.springframework.core.io.ByteArrayResource(videoBytes) {
                @Override
                public String getFilename() {
                    return filename != null ? filename : "avatar_sample.mp4";
                }
            };
            form.add("file", fileResource);

            HttpEntity<org.springframework.util.MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(form, headers);
            ResponseEntity<String> response = restTemplate.postForEntity("https://api.heygen.com/v3/assets", requestEntity, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            log.info("HeyGen v3 asset upload response: {}", root);
            if (root.has("data")) {
                JsonNode data = root.get("data");
                if (data.has("id")) return data.get("id").asText();
                if (data.has("asset_id")) return data.get("asset_id").asText();
            }
        } catch (Exception e) {
            log.warn("HeyGen video asset upload failed: {}. Falling back to synthetic asset ID.", e.getMessage());
        }
        return "asset_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public Map<String, String> createHeyGenDigitalTwin(String name, String assetId) {
        Map<String, String> result = new HashMap<>();
        String safeName = (name != null && !name.isBlank()) ? name : "Instant Video Avatar";
        result.put("name", safeName);

        if (!isRealKey(heygenKey)) {
            log.warn("HeyGen API key is unconfigured. Generating synthetic digital twin avatar.");
            String syntheticId = "avatar_twin_" + UUID.randomUUID().toString().substring(0, 8);
            result.put("avatar_id", syntheticId);
            result.put("look_id", "look_" + UUID.randomUUID().toString().substring(0, 8));
            result.put("group_id", "group_" + UUID.randomUUID().toString().substring(0, 8));
            result.put("status", "COMPLETED");
            return result;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Api-Key", heygenKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new HashMap<>();
            body.put("type", "digital_twin");
            body.put("name", safeName);
            body.put("file", Map.of(
                    "type", "asset_id",
                    "asset_id", assetId
            ));

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity("https://api.heygen.com/v3/avatars", entity, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            log.info("HeyGen digital twin creation response: {}", root);

            if (root.has("error") && !root.get("error").isNull()) {
                String errorMsg = root.get("error").has("message") ? root.get("error").get("message").asText() : root.get("error").toString();
                result.put("status", "FAILED");
                result.put("error", errorMsg);
                return result;
            }

            JsonNode data = root.path("data");
            String lookId = data.path("look_id").asText(null);
            String avatarId = data.path("avatar_id").asText(null);
            String groupId = data.path("avatar_group_id").asText(null);
            String previewImageUrl = null;
            String consentStatus = null;

            if (data.has("avatar_group")) {
                JsonNode groupNode = data.path("avatar_group");
                if (groupId == null || groupId.isBlank()) {
                    groupId = groupNode.path("id").asText(null);
                }
                consentStatus = groupNode.path("consent_status").asText(null);
                if (consentStatus == null || consentStatus.isBlank()) {
                    consentStatus = groupNode.path("status").asText(null);
                }
                previewImageUrl = groupNode.path("preview_image_url").asText(null);
            }

            if (data.has("avatar_item")) {
                JsonNode itemNode = data.path("avatar_item");
                if (lookId == null || lookId.isBlank()) {
                    lookId = itemNode.path("id").asText(null);
                }
                if (avatarId == null || avatarId.isBlank()) {
                    avatarId = itemNode.path("id").asText(null);
                }
                if (previewImageUrl == null || previewImageUrl.isBlank()) {
                    previewImageUrl = itemNode.path("preview_image_url").asText(null);
                }
            }

            if (avatarId == null || avatarId.isBlank()) {
                avatarId = data.path("id").asText(null);
            }
            if (lookId == null || lookId.isBlank()) {
                lookId = data.path("id").asText(null);
            }
            if (avatarId == null || avatarId.isBlank()) {
                avatarId = lookId != null ? lookId : "avatar_" + UUID.randomUUID().toString().substring(0, 8);
            }

            result.put("look_id", lookId);
            result.put("avatar_id", avatarId);
            result.put("group_id", groupId);
            result.put("preview_image_url", previewImageUrl);
            result.put("consent_status", consentStatus);

            if ("pending".equalsIgnoreCase(consentStatus) || "pending_consent".equalsIgnoreCase(consentStatus)) {
                result.put("status", "PENDING_CONSENT");
            } else {
                result.put("status", "PROCESSING");
            }
            return result;
        } catch (Exception e) {
            log.error("HeyGen digital twin creation failed: {}", e.getMessage());
            result.put("status", "FAILED");
            result.put("error", e.getMessage());
            result.put("avatar_id", "avatar_twin_" + UUID.randomUUID().toString().substring(0, 8));
            return result;
        }
    }

    public Map<String, String> getHeyGenConsentSession(String groupId) {
        Map<String, String> result = new HashMap<>();
        if (!isRealKey(heygenKey) || groupId == null || groupId.isBlank()) {
            return result;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Api-Key", heygenKey);
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>("{}", headers);

            String url = "https://api.heygen.com/v3/avatars/" + groupId + "/consent";
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            JsonNode data = root.path("data");
            String consentUrl = data.path("url").asText(null);
            String consentStatus = data.path("avatar_group").path("consent_status").asText("pending");

            result.put("url", consentUrl);
            result.put("consent_status", consentStatus);
            result.put("group_id", groupId);
            return result;
        } catch (Exception e) {
            log.warn("Failed to get HeyGen consent session for groupId {}: {}", groupId, e.getMessage());
            result.put("error", e.getMessage());
            return result;
        }
    }

    public Map<String, String> checkHeyGenDigitalTwinStatus(String lookId, String groupId) {
        Map<String, String> result = new HashMap<>();
        if (!isRealKey(heygenKey)) {
            result.put("status", "COMPLETED");
            return result;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Api-Key", heygenKey);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            // 1. If groupId exists, check group consent and lifecycle
            if (groupId != null && !groupId.isBlank()) {
                try {
                    String groupUrl = "https://api.heygen.com/v3/avatars/" + groupId;
                    ResponseEntity<String> groupResp = restTemplate.exchange(groupUrl, HttpMethod.GET, entity, String.class);
                    if (groupResp.getStatusCode().is2xxSuccessful() && groupResp.getBody() != null) {
                        JsonNode groupRoot = objectMapper.readTree(groupResp.getBody());
                        JsonNode groupData = groupRoot.path("data");
                        String consentStatus = groupData.path("consent_status").asText(null);
                        String groupStatus = groupData.path("status").asText(null);
                        String previewUrl = groupData.path("preview_image_url").asText(null);

                        if (previewUrl != null && !previewUrl.isBlank()) {
                            result.put("thumbnailUrl", previewUrl);
                        }
                        if (consentStatus != null) {
                            result.put("consent_status", consentStatus);
                        }

                        if ("pending".equalsIgnoreCase(consentStatus) || "pending_consent".equalsIgnoreCase(groupStatus)) {
                            result.put("status", "PENDING_CONSENT");
                            result.put("statusMessage", "HeyGen requires verbal consent verification before activating this digital twin.");
                            return result;
                        }
                    }
                } catch (Exception groupEx) {
                    log.debug("Avatar group check notice: {}", groupEx.getMessage());
                }
            }

            // 2. If lookId exists, check look rendering status
            if (lookId != null && !lookId.isBlank() && !lookId.startsWith("look_")) {
                String url = "https://api.heygen.com/v3/avatars/looks/" + lookId;
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                JsonNode root = objectMapper.readTree(response.getBody());

                JsonNode data = root.path("data");
                String status = data.path("status").asText("PROCESSING").toUpperCase();
                String avatarId = data.path("avatar_id").asText(null);
                if (avatarId == null || avatarId.isBlank()) {
                    avatarId = data.path("id").asText(lookId);
                }

                if (data.has("error") && !data.path("error").isMissingNode()) {
                    JsonNode errNode = data.path("error");
                    String errMsg = errNode.has("message") ? errNode.path("message").asText() : errNode.asText();
                    result.put("error", errMsg);
                }

                result.put("status", status);
                result.put("avatar_id", avatarId);
                return result;
            }

            result.put("status", "PROCESSING");
            return result;
        } catch (Exception e) {
            log.warn("Check HeyGen digital twin status failed for lookId {} / groupId {}: {}", lookId, groupId, e.getMessage());
            result.put("status", "PROCESSING");
            result.put("error", e.getMessage());
            return result;
        }
    }

    public boolean deleteHeyGenAvatarGroup(String groupId) {
        if (!isRealKey(heygenKey) || groupId == null || groupId.isBlank()) {
            return false;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Api-Key", heygenKey);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            String url = "https://api.heygen.com/v3/avatars/" + groupId;
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.DELETE, entity, String.class);
            log.info("HeyGen delete avatar group {} response status: {}", groupId, response.getStatusCode());
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("Failed to delete HeyGen avatar group {}: {}", groupId, e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // 3. HEYGEN: AVATAR GENERATION (ADAPTER: AVATAR III / AVATAR IV)
    // =========================================================================
    public GeneratedAssetEntity generateAvatarVideo(Long postId, Long sceneId, AvatarEngine engine,
                                                    String avatarId, String spokenText, int durationSeconds,
                                                    String voiceAudioUrl) throws Exception {
        log.info("Generating HeyGen avatar ({}) for post {}, scene {} with audio-driven lip-sync...", engine, postId, sceneId);
        if (!isRealKey(heygenKey)) {
            throw new IllegalStateException("HeyGen API key is unconfigured. Please configure 'heygen.api.key' in Master Settings.");
        }

        MediaJobEntity job = recordJob(postId, sceneId, JobProvider.HEYGEN, "GENERATE_AVATAR_" + engine);
        double ratePerSec = (engine == AvatarEngine.AVATAR_III) ? (1.0 / 60.0) : (4.0 / 60.0);
        double cost = durationSeconds * ratePerSec;

        // 1. Check if an existing HeyGen videoId was already completed or in-progress for this post
        String videoId = null;
        List<MediaJobEntity> previousJobs = mediaJobRepository.findByPostIdOrderByStartedAtDesc(postId);
        for (MediaJobEntity mj : previousJobs) {
            if (mj.getProvider() == JobProvider.HEYGEN) {
                String candidateId = null;
                if (mj.getProviderTaskId() != null && !mj.getProviderTaskId().isBlank() && !mj.getProviderTaskId().startsWith("task_")) {
                    candidateId = mj.getProviderTaskId();
                } else if (mj.getErrorJson() != null && mj.getErrorJson().contains("videoId: ")) {
                    String part = mj.getErrorJson().substring(mj.getErrorJson().indexOf("videoId: ") + 9).trim();
                    if (!part.isBlank()) {
                        candidateId = part.split("[^a-zA-Z0-9_]")[0];
                    }
                }

                if (candidateId != null && !candidateId.isBlank()) {
                    String candidateStatus = checkHeyGenVideoStatus(candidateId);
                    if ("completed".equalsIgnoreCase(candidateStatus) || "done".equalsIgnoreCase(candidateStatus) || "success".equalsIgnoreCase(candidateStatus)) {
                        log.info("Found completed HeyGen videoId '{}' for post {}. Reusing finished render!", candidateId, postId);
                        videoId = candidateId;
                        break;
                    } else if ("processing".equalsIgnoreCase(candidateStatus) || "waiting".equalsIgnoreCase(candidateStatus) || "pending".equalsIgnoreCase(candidateStatus)) {
                        log.info("Found actively rendering HeyGen videoId '{}' for post {}. Continuing async wait...", candidateId, postId);
                        videoId = candidateId;
                        break;
                    } else {
                        log.warn("Previous HeyGen videoId '{}' is in failed/invalid state on server ({}). Discarding and generating fresh video.", candidateId, candidateStatus);
                    }
                }
            }
        }

        try {
            // Only submit a new generation request if no valid completed or in-progress videoId exists
            if (videoId == null) {
                String targetUrl = (heygenV2Url != null && !heygenV2Url.isEmpty()) ? heygenV2Url : "https://api.heygen.com/v2/video/generate";
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-Api-Key", heygenKey);
                headers.setContentType(MediaType.APPLICATION_JSON);

                Map<String, Object> characterMap = new HashMap<>();
                if (avatarId == null || avatarId.isBlank()) {
                    throw new IllegalStateException("Presenter avatar ID is missing or unconfigured.");
                }

                boolean isKnownVideoAvatar = videoAvatarRepository != null && videoAvatarRepository.existsByAvatarId(avatarId);
                boolean isExplicitPhoto = avatarId.contains("photo") || avatarId.startsWith("talking_photo_");
                boolean isTalkingPhoto = !isKnownVideoAvatar && (isExplicitPhoto || (avatarId.matches("^[a-f0-9]{32}$") && !avatarId.contains("_")));

                if (isTalkingPhoto) {
                    characterMap.put("type", "talking_photo");
                    characterMap.put("talking_photo_id", avatarId);
                    log.info("Using dynamic HeyGen talking photo ID: '{}'", avatarId);
                } else {
                    characterMap.put("type", "avatar");
                    characterMap.put("avatar_id", avatarId);
                    characterMap.put("avatar_style", "normal");
                    log.info("Using dynamic HeyGen live video avatar ID: '{}' (hand gestures & body movement enabled)", avatarId);
                }

                // Direct ElevenLabs Audio Lip-Sync (No double generation, no voiceId needed)
                Map<String, Object> voiceMap = new HashMap<>();
                if (voiceAudioUrl != null && !voiceAudioUrl.isBlank()) {
                    voiceMap.put("type", "audio");
                    String externalAudioUrl = storageService.getPresignedUrl(voiceAudioUrl, Duration.ofHours(24));
                    voiceMap.put("audio_url", externalAudioUrl);
                } else {
                    voiceMap.put("type", "text");
                    voiceMap.put("input_text", (spokenText != null && !spokenText.isBlank()) ? spokenText : "Hello, welcome.");
                    voiceMap.put("voice_id", "1bd801e7e50f4715b92c10829a6e3b3e");
                }

                Map<String, Object> input = new HashMap<>();
                input.put("character", characterMap);
                input.put("voice", voiceMap);

                // Dynamic Background Injection: Only replace if there is an active default background
                Optional<BackgroundAssetEntity> defaultBg = backgroundRepository.findByIsDefaultTrue();
                if (defaultBg.isPresent()) {
                    // Enable AI background removal (matting) to isolate the avatar from its original recorded room/studio footage
                    characterMap.put("matting", true);

                    String bgType = defaultBg.get().getMediaType().equalsIgnoreCase("VIDEO") ? "video" : "image";
                    String externalBgUrl = storageService.getPresignedUrl(defaultBg.get().getStorageUrl(), Duration.ofHours(24));
                    input.put("background", Map.of(
                            "type", bgType,
                            "url", externalBgUrl
                    ));
                    log.info("Active default background '{}' ({}) injected with AI matting into HeyGen avatar scene", defaultBg.get().getName(), bgType);
                } else {
                    // Keep native trained background as-is (no matting, no background layer)
                    characterMap.put("matting", false);
                    log.info("No active default background selected. Preserving avatar's native trained video background.");
                }

                String videoAspectRatio = "9:16";
                if (masterSettingsService != null) {
                    try {
                        Map<String, Object> settings = masterSettingsService.getAllSettings();
                        if (settings.containsKey("videoAspectRatio") && settings.get("videoAspectRatio") != null) {
                            videoAspectRatio = String.valueOf(settings.get("videoAspectRatio")).trim();
                        }
                    } catch (Exception ignored) {}
                }
                VideoDimensions dims = VideoDimensions.fromAspectRatio(videoAspectRatio);

                Map<String, Object> payload = new HashMap<>();
                payload.put("video_inputs", List.of(input));
                payload.put("dimension", Map.of("width", dims.heygenWidth, "height", dims.heygenHeight));

                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
                ResponseEntity<String> response = restTemplate.postForEntity(targetUrl, entity, String.class);

                JsonNode root = objectMapper.readTree(response.getBody());
                if (root.has("error") && !root.get("error").isNull()) {
                    String errorMsg = root.get("error").isTextual() ? root.get("error").asText() : root.get("error").toString();
                    throw new RuntimeException("HeyGen API Error: " + errorMsg);
                }

                if (root.has("data") && root.get("data").has("video_id")) {
                    videoId = root.get("data").get("video_id").asText();
                    job.setProviderTaskId(videoId);
                    mediaJobRepository.save(job);
                }
            }

            completeJob(job, cost);
            recordUsage(postId, "HEYGEN", engine.name(), (double) durationSeconds, "SECONDS", cost);

            String videoUrl = "https://app.heygen.com/share/" + (videoId != null ? videoId : "video_" + System.currentTimeMillis());
            String realMp4Url = null;
            if (videoId != null && !videoId.startsWith("video_")) {
                // Poll asynchronously without artificial timeout â€” wait until completed or explicitly failed
                realMp4Url = pollHeyGenVideoUrl(videoId);
                if (realMp4Url != null && !realMp4Url.isBlank()) {
                    try {
                        byte[] videoBytes = restTemplate.getForObject(realMp4Url, byte[].class);
                        if (videoBytes != null && videoBytes.length > 0) {
                            return storageService.saveGeneratedMedia(
                                    videoBytes, ".mp4", "video/mp4", AssetType.VIDEO, postId, sceneId,
                                    (double) durationSeconds,
                                    String.format("{\"provider\":\"HeyGen\",\"engine\":\"%s\",\"videoId\":\"%s\",\"source\":\"%s\"}", engine, videoId, realMp4Url)
                            );
                        }
                    } catch (Exception dlEx) {
                        log.warn("Could not persist HeyGen mp4 to S3, using direct CDN URL: {}", dlEx.getMessage());
                        if (realMp4Url != null && realMp4Url.length() <= 250) {
                            videoUrl = realMp4Url;
                        }
                    }
                } else {
                    throw new RuntimeException("HeyGen video generation failed to produce video URL for videoId: " + videoId);
                }
            }

            return GeneratedAssetEntity.builder()
                    .postId(postId)
                    .sceneId(sceneId)
                    .assetType(AssetType.VIDEO)
                    .storageUrl(videoUrl)
                    .storagePath("heygen://video/" + (videoId != null ? videoId : "video_" + System.currentTimeMillis()))
                    .s3Key(storageService.extractS3Key(videoUrl))
                    .mimeType("video/mp4")
                    .durationSeconds((double) durationSeconds)
                    .metadataJson(String.format("{\"provider\":\"HeyGen\",\"engine\":\"%s\",\"videoId\":\"%s\",\"source\":\"%s\"}", engine, videoId, realMp4Url != null ? realMp4Url : ""))
                    .createdAt(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            String errorMsg = extractHeyGenErrorMessage(e);
            log.error("HeyGen video generation failed: {}", errorMsg);
            job.setStatus(JobStatus.FAILED);
            job.setErrorJson(errorMsg);
            mediaJobRepository.save(job);
            throw new RuntimeException("HeyGen Avatar Generation Failed: " + errorMsg, e);
        }
    }

    public String checkHeyGenVideoStatus(String videoId) {
        if (!isRealKey(heygenKey) || videoId == null || videoId.isBlank() || videoId.startsWith("video_")) {
            return null;
        }
        try {
            String url = (heygenStatusUrl != null && !heygenStatusUrl.isBlank())
                    ? heygenStatusUrl + "?video_id=" + videoId
                    : "https://api.heygen.com/v1/video_status.get?video_id=" + videoId;
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Api-Key", heygenKey);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                return root.path("data").path("status").asText();
            }
        } catch (Exception e) {
            log.debug("Error checking single status for {}: {}", videoId, e.getMessage());
        }
        return null;
    }

    public String pollHeyGenVideoUrl(String videoId) {
        if (!isRealKey(heygenKey) || videoId == null || videoId.isBlank() || videoId.startsWith("video_")) {
            return null;
        }
        log.info("Polling HeyGen video status asynchronously for videoId: {}...", videoId);
        long startTime = System.currentTimeMillis();
        // Generous safety ceiling (60 minutes) to avoid infinite loop only if HeyGen server completely loses record
        long maxSafetyMillis = 3600 * 1000L;
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", heygenKey);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        while (System.currentTimeMillis() - startTime < maxSafetyMillis) {
            try {
                String url = (heygenStatusUrl != null && !heygenStatusUrl.isBlank())
                        ? heygenStatusUrl + "?video_id=" + videoId
                        : "https://api.heygen.com/v1/video_status.get?video_id=" + videoId;
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    JsonNode root = objectMapper.readTree(response.getBody());
                    JsonNode dataNode = root.path("data");
                    String status = dataNode.path("status").asText();
                    if ("completed".equalsIgnoreCase(status) || "done".equalsIgnoreCase(status) || "success".equalsIgnoreCase(status)) {
                        String realUrl = dataNode.path("video_url").asText();
                        if (!realUrl.isBlank()) {
                            log.info("HeyGen video {} completed successfully: {}", videoId, realUrl);
                            return realUrl;
                        }
                    } else if ("failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status)) {
                        JsonNode errNode = dataNode.path("error");
                        String errDetail = errNode.isTextual() ? errNode.asText() : errNode.toString();
                        log.error("HeyGen video {} explicitly failed on server: {}", videoId, errDetail);
                        throw new RuntimeException("HeyGen video processing failed on server: " + errDetail);
                    } else {
                        // Actively rendering/processing/queued â€” wait asynchronously without timing out
                        long elapsedSec = (System.currentTimeMillis() - startTime) / 1000;
                        if (elapsedSec % 20 == 0) {
                            log.info("HeyGen video {} is currently '{}' (elapsed: {}s). Waiting for completion...", videoId, status, elapsedSec);
                        }
                    }
                }
            } catch (RuntimeException re) {
                if (re.getMessage() != null && re.getMessage().startsWith("HeyGen video processing failed")) {
                    throw re;
                }
                log.debug("HeyGen status polling notice: {}", re.getMessage());
            } catch (Exception e) {
                log.debug("HeyGen status polling notice: {}", e.getMessage());
            }

            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {}
        }

        log.warn("HeyGen video {} polling reached 60-minute safety ceiling without completion", videoId);
        return null;
    }

    public String extractHeyGenErrorMessage(Exception e) {
        if (e instanceof org.springframework.web.client.HttpStatusCodeException httpEx) {
            String body = httpEx.getResponseBodyAsString();
            try {
                JsonNode root = objectMapper.readTree(body);
                if (root.has("error") && root.get("error").has("message")) {
                    return root.get("error").get("message").asText();
                } else if (root.has("message")) {
                    return root.get("message").asText();
                }
            } catch (Exception ignored) {}
            return "HTTP " + httpEx.getStatusCode().value() + ": " + body;
        }
        return e.getMessage() != null ? e.getMessage() : "Unknown HeyGen connection error";
    }

    // =========================================================================
    // 4. RUNWAY: TEXT-TO-VIDEO CINEMATIC B-ROLL (GEN-4.5)
    // =========================================================================
    public GeneratedAssetEntity generateRunwayBroll(Long postId, Long sceneId, String promptText, int durationSeconds) throws Exception {
        return generateRunwayBroll(postId, sceneId, promptText, null, durationSeconds);
    }

    public GeneratedAssetEntity generateRunwayBroll(Long postId, Long sceneId, String promptText, String inputImageUrl, int durationSeconds) throws Exception {
        log.info("Generating Runway Gen-4.5 B-roll for post {}, scene {}...", postId, sceneId);
        if (!isRealKey(runwayKey)) {
            throw new IllegalStateException("Runway API key is unconfigured. Please configure 'runway.api.key' in Master Settings.");
        }

        MediaJobEntity job = recordJob(postId, sceneId, JobProvider.RUNWAY, "GENERATE_BROLL_RUNWAY");
        double cost = durationSeconds * 0.12;

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(runwayKey);
            headers.set("X-Runway-Version", "2024-11-06");
            headers.setContentType(MediaType.APPLICATION_JSON);

            String videoAspectRatio = "9:16";
            if (masterSettingsService != null) {
                try {
                    Map<String, Object> settings = masterSettingsService.getAllSettings();
                    if (settings.containsKey("videoAspectRatio") && settings.get("videoAspectRatio") != null) {
                        videoAspectRatio = String.valueOf(settings.get("videoAspectRatio")).trim();
                    }
                } catch (Exception ignored) {}
            }
            VideoDimensions dims = VideoDimensions.fromAspectRatio(videoAspectRatio);

            Map<String, Object> body = new HashMap<>();
            body.put("model", runwayModel);
            body.put("promptText", promptText);
            body.put("ratio", dims.runwayRatio);
            int runwayDuration = (durationSeconds > 7) ? 10 : 5;
            body.put("duration", runwayDuration);

            if (inputImageUrl != null && !inputImageUrl.isBlank()) {
                String signedImageUrl = storageService.getPresignedUrl(inputImageUrl, Duration.ofHours(24));
                body.put("promptImage", signedImageUrl);
                log.info("Supplied on-demand presigned prompt image to Runway: {}", signedImageUrl.substring(0, Math.min(80, signedImageUrl.length())));
            }

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(runwayUrl, entity, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            if (root.has("error") && !root.get("error").isNull()) {
                String errorMsg = root.get("error").isTextual() ? root.get("error").asText() : root.get("error").toString();
                throw new RuntimeException("Runway API Error: " + errorMsg);
            }

            String taskId = root.has("id") ? root.get("id").asText() : null;
            completeJob(job, cost);
            recordUsage(postId, "RUNWAY", runwayModel, (double) durationSeconds, "SECONDS", cost);

            String videoUrl = "https://runwayml.com/tasks/" + (taskId != null ? taskId : "task_" + System.currentTimeMillis());
            String realMp4Url = null;
            if (taskId != null && !taskId.startsWith("task_")) {
                realMp4Url = pollRunwayVideoUrl(taskId, 240);
                if (realMp4Url != null && !realMp4Url.isBlank()) {
                    try {
                        byte[] videoBytes = restTemplate.getForObject(realMp4Url, byte[].class);
                        if (videoBytes != null && videoBytes.length > 0) {
                            return storageService.saveGeneratedMedia(
                                    videoBytes, ".mp4", "video/mp4", AssetType.VIDEO, postId, sceneId,
                                    (double) durationSeconds,
                                    String.format("{\"provider\":\"Runway\",\"model\":\"%s\",\"taskId\":\"%s\",\"source\":\"%s\"}", runwayModel, taskId, realMp4Url)
                            );
                        }
                    } catch (Exception dlEx) {
                        log.warn("Could not persist Runway mp4 to S3, using direct CDN URL: {}", dlEx.getMessage());
                        if (realMp4Url != null && realMp4Url.length() <= 250) {
                            videoUrl = realMp4Url;
                        }
                    }
                } else {
                    throw new RuntimeException("Runway task timed out or failed to produce video URL for taskId: " + taskId);
                }
            }

            return GeneratedAssetEntity.builder()
                    .postId(postId)
                    .sceneId(sceneId)
                    .assetType(AssetType.VIDEO)
                    .storageUrl(videoUrl)
                    .storagePath("runway://task/" + (taskId != null ? taskId : "task_" + System.currentTimeMillis()))
                    .s3Key(storageService.extractS3Key(videoUrl))
                    .mimeType("video/mp4")
                    .durationSeconds((double) durationSeconds)
                    .metadataJson(String.format("{\"provider\":\"Runway\",\"model\":\"%s\",\"taskId\":\"%s\",\"source\":\"%s\"}", runwayModel, taskId, realMp4Url != null ? realMp4Url : ""))
                    .createdAt(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            String errorMsg = extractRunwayErrorMessage(e);
            log.error("Runway text-to-video failed: {}", errorMsg);
            job.setStatus(JobStatus.FAILED);
            job.setErrorJson(errorMsg);
            mediaJobRepository.save(job);
            throw new RuntimeException("Runway B-Roll Generation Failed: " + errorMsg, e);
        }
    }

    public String pollRunwayVideoUrl(String taskId, int maxWaitSeconds) {
        if (!isRealKey(runwayKey) || taskId == null || taskId.isBlank() || taskId.startsWith("task_")) {
            return null;
        }
        log.info("Polling Runway task status for taskId: {}...", taskId);
        long startTime = System.currentTimeMillis();
        long maxWaitMillis = maxWaitSeconds * 1000L;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(runwayKey);
        headers.set("X-Runway-Version", "2024-11-06");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        String taskBaseUrl = (runwayTasksUrl != null && !runwayTasksUrl.isBlank())
                ? runwayTasksUrl
                : "https://api.dev.runwayml.com/v1/tasks";
        String url = taskBaseUrl + "/" + taskId;

        while (System.currentTimeMillis() - startTime < maxWaitMillis) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    JsonNode root = objectMapper.readTree(response.getBody());
                    String status = root.path("status").asText();
                    if ("SUCCEEDED".equalsIgnoreCase(status)) {
                        JsonNode output = root.path("output");
                        if (output.isArray() && output.size() > 0) {
                            String realUrl = output.get(0).asText();
                            log.info("Runway task {} completed successfully: {}", taskId, realUrl);
                            return realUrl;
                        }
                    } else if ("FAILED".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(status)) {
                        JsonNode failureNode = root.path("failure");
                        String failureMsg = failureNode.isTextual() ? failureNode.asText() : failureNode.toString();
                        log.error("Runway task {} failed: {}", taskId, failureMsg);
                        return null;
                    }
                }
            } catch (Exception e) {
                log.debug("Runway status polling notice: {}", e.getMessage());
            }
            try {
                Thread.sleep(4000);
            } catch (InterruptedException ignored) {}
        }
        log.warn("Runway task {} polling timed out after {}s", taskId, maxWaitSeconds);
        return null;
    }

    public String extractRunwayErrorMessage(Exception e) {
        if (e instanceof org.springframework.web.client.HttpStatusCodeException httpEx) {
            String body = httpEx.getResponseBodyAsString();
            try {
                JsonNode root = objectMapper.readTree(body);
                if (root.has("error")) {
                    return root.get("error").isTextual() ? root.get("error").asText() : root.get("error").toString();
                }
            } catch (Exception ignored) {}
            return "HTTP " + httpEx.getStatusCode().value() + ": " + body;
        }
        return e.getMessage() != null ? e.getMessage() : "Unknown Runway connection error";
    }

    private record CaptionCue(double startSec, double endSec, String text) {}

    // =========================================================================
    // 5. FFMPEG COMPOSITION (Joining Avatar, B-Roll, Subtitles, Audio)
    // =========================================================================
    public GeneratedAssetEntity compositeVideo(Long postId, int totalDurationSeconds, String topic, String avatarVideoUrl) throws Exception {
        return compositeVideo(postId, totalDurationSeconds, topic, avatarVideoUrl, null, null);
    }

    public GeneratedAssetEntity compositeVideo(Long postId, int totalDurationSeconds, String topic,
                                               String avatarVideoUrl, List<ContentSceneEntity> scenes) throws Exception {
        return compositeVideo(postId, totalDurationSeconds, topic, avatarVideoUrl, scenes, null);
    }

    public GeneratedAssetEntity compositeVideo(Long postId, int totalDurationSeconds, String topic,
                                               String avatarVideoUrl, List<ContentSceneEntity> scenes,
                                               String spokenScript) throws Exception {
        String videoAspectRatio = "9:16";
        String brandLogoUrl = null;
        boolean subtitlesEnabled = false;
        String captionLanguage = "Hinglish";
        if (masterSettingsService != null) {
            try {
                Map<String, Object> settings = masterSettingsService.getAllSettings();
                brandLogoUrl = (String) settings.get("brandLogoUrl");
                Object subObj = settings.get("subtitlesEnabled");
                if (subObj instanceof Boolean b) {
                    subtitlesEnabled = b;
                } else if (subObj != null) {
                    subtitlesEnabled = Boolean.parseBoolean(String.valueOf(subObj));
                }
                Object langObj = settings.get("captionLanguage");
                if (langObj != null && !String.valueOf(langObj).isBlank()) {
                    captionLanguage = String.valueOf(langObj).trim();
                }
                Object ratioObj = settings.get("videoAspectRatio");
                if (ratioObj != null && !String.valueOf(ratioObj).isBlank()) {
                    videoAspectRatio = String.valueOf(ratioObj).trim();
                }
            } catch (Exception ignored) {}
        }
        VideoDimensions dims = VideoDimensions.fromAspectRatio(videoAspectRatio);

        log.info("Compositing final {} ({}) video Reel for post {}...", videoAspectRatio, dims.resolution, postId);
        MediaJobEntity job = recordJob(postId, null, JobProvider.FFMPEG, "COMPOSITE_VIDEO");

        String effectiveSpokenScript = (spokenScript != null && !spokenScript.isBlank())
                ? spokenScript.trim()
                : buildSpokenScriptFromScenes(scenes);

        String finalUrl = (avatarVideoUrl != null && !avatarVideoUrl.isBlank()) ? avatarVideoUrl : "https://app.heygen.com/share/composite_" + postId;

        List<ContentSceneEntity> brollScenes = (scenes != null) ? scenes.stream()
                .filter(s -> s.getSceneType() == SceneType.BROLL && s.getMediaUrl() != null && !s.getMediaUrl().isBlank())
                .sorted(Comparator.comparingInt(ContentSceneEntity::getSceneNumber))
                .toList() : List.of();

        boolean needsComposite = !brollScenes.isEmpty()
                || (brandLogoUrl != null && !brandLogoUrl.isBlank())
                || subtitlesEnabled
                || !"9:16".equals(videoAspectRatio);

        if (avatarVideoUrl != null && !avatarVideoUrl.isBlank() && needsComposite) {
            try {
                String ffmpegBin = findFfmpegBinary();
                if (ffmpegBin != null) {
                    finalUrl = executeFfmpegComposite(
                            ffmpegBin, postId, totalDurationSeconds, avatarVideoUrl,
                            brollScenes, brandLogoUrl, subtitlesEnabled, captionLanguage, effectiveSpokenScript,
                            dims
                    );
                }
            } catch (Exception e) {
                log.warn("FFmpeg multi-scene composition encountered error: {}. Gracefully using avatar video.", e.getMessage(), e);
            }
        }

        completeJob(job, 0.05);
        recordUsage(postId, "FFMPEG", "Composition_Worker", (double) totalDurationSeconds, "SECONDS", 0.05);

        String cleanS3Key = storageService.extractS3Key(finalUrl);
        return GeneratedAssetEntity.builder()
                .postId(postId)
                .assetType(AssetType.VIDEO)
                .storageUrl(finalUrl)
                .storagePath(cleanS3Key != null && !cleanS3Key.startsWith("http") ? "s3://" + cleanS3Key : finalUrl)
                .s3Key(cleanS3Key)
                .mimeType("video/mp4")
                .durationSeconds((double) totalDurationSeconds)
                .metadataJson(String.format(
                        "{\"postId\":%d,\"topic\":\"%s\",\"composedScenes\":%d,\"captionsEnabled\":%s,\"captionLanguage\":\"%s\",\"aspectRatio\":\"%s\",\"resolution\":\"%s\"}",
                        postId, topic != null ? topic.replace("\"", "'") : "", brollScenes.size() + 1, subtitlesEnabled, captionLanguage, videoAspectRatio, dims.resolution))
                .createdAt(LocalDateTime.now())
                .build();
    }

    private String buildSpokenScriptFromScenes(List<ContentSceneEntity> scenes) {
        if (scenes == null || scenes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        scenes.stream()
                .sorted(Comparator.comparingInt(s -> s.getSceneNumber() != null ? s.getSceneNumber() : 0))
                .forEach(s -> {
                    if (s.getSpokenText() != null && !s.getSpokenText().isBlank()) {
                        if (sb.length() > 0) sb.append(" ");
                        sb.append(s.getSpokenText().trim());
                    }
                });
        return sb.toString();
    }

    public String findFfmpegBinary() {
        File projectBin = new File("c:\\projects\\cf\\bin\\ffmpeg.exe");
        if (projectBin.exists() && projectBin.canExecute()) {
            return projectBin.getAbsolutePath();
        }
        File wingetBin = new File("C:\\Users\\Admin\\AppData\\Local\\Microsoft\\WinGet\\Packages\\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\\ffmpeg-9.0.2-full_build\\bin\\ffmpeg.exe");
        if (wingetBin.exists() && wingetBin.canExecute()) {
            return wingetBin.getAbsolutePath();
        }
        return "ffmpeg";
    }

    private String executeFfmpegComposite(String ffmpegBin, Long postId, int totalDurationSeconds, String avatarUrl,
                                          List<ContentSceneEntity> brolls, String brandLogoUrl,
                                          boolean subtitlesEnabled, String captionLanguage,
                                          String spokenScript, VideoDimensions dims) throws Exception {
        Path tempDir = Files.createTempDirectory("comp_post_" + postId + "_");
        Process proc = null;
        try {
            // 1. Download Avatar Video
            Path avatarFile = tempDir.resolve("avatar.mp4");
            downloadFile(avatarUrl, avatarFile);

            // 2. Download B-roll videos
            List<Path> brollFiles = new ArrayList<>();
            for (int i = 0; i < brolls.size(); i++) {
                Path brollFile = tempDir.resolve("broll_" + (i + 1) + ".mp4");
                downloadFile(brolls.get(i).getMediaUrl(), brollFile);
                brollFiles.add(brollFile);
            }

            // 3. Download Brand Logo if configured
            Path logoFile = null;
            if (brandLogoUrl != null && !brandLogoUrl.isBlank()) {
                try {
                    Path candidateLogo = tempDir.resolve("brand_logo.png");
                    downloadFile(brandLogoUrl, candidateLogo);
                    if (Files.exists(candidateLogo) && Files.size(candidateLogo) > 0) {
                        logoFile = candidateLogo;
                    }
                } catch (Exception logoEx) {
                    log.warn("Could not download brand logo for video overlay: {}", logoEx.getMessage());
                }
            }

            // 4. Generate ASS captions if enabled
            boolean hasAssCaptions = false;
            if (subtitlesEnabled) {
                try {
                    Path audioFile = tempDir.resolve("caption_audio.mp3");
                    extractAudioForCaptions(ffmpegBin, avatarFile, audioFile);
                    List<CaptionCue> cues = buildCaptionCues(postId, audioFile, totalDurationSeconds, spokenScript, captionLanguage);
                    if (!cues.isEmpty()) {
                        String assContent = buildAssSubtitles(cues, dims);
                        Path assFile = tempDir.resolve("captions.ass");
                        Files.writeString(assFile, assContent, StandardCharsets.UTF_8);
                        hasAssCaptions = Files.exists(assFile) && Files.size(assFile) > 0;
                        log.info("Generated {} timed caption cues ({}) for post {}", cues.size(), captionLanguage, postId);
                    }
                } catch (Exception capEx) {
                    log.warn("Caption generation skipped due to error for post {}: {}", postId, capEx.getMessage(), capEx);
                }
            }

            Path outputFile = tempDir.resolve("output_composite.mp4");
            Path logFile = tempDir.resolve("ffmpeg_composite.log");

            // 5. Build FFmpeg command with cutaways, logo watermark, and ASS captions filter
            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegBin);
            cmd.add("-y");
            cmd.add("-i");
            cmd.add(avatarFile.toAbsolutePath().toString());

            for (Path bf : brollFiles) {
                cmd.add("-i");
                cmd.add(bf.toAbsolutePath().toString());
            }

            int logoInputIdx = -1;
            if (logoFile != null) {
                logoInputIdx = 1 + brollFiles.size();
                cmd.add("-i");
                cmd.add(logoFile.toAbsolutePath().toString());
            }

            StringBuilder filter = new StringBuilder();
            // Conform base avatar video to standard dimensions (9:16, 1:1, or 16:9)
            filter.append(String.format("[0:v]scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,setsar=1[v0];",
                    dims.width, dims.height, dims.width, dims.height));

            for (int i = 0; i < brolls.size(); i++) {
                ContentSceneEntity sc = brolls.get(i);
                int start = sc.getStartTimeSeconds() != null ? sc.getStartTimeSeconds() : (10 + i * 15);
                filter.append(String.format("[%d:v]scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,setsar=1,setpts=PTS-STARTPTS+%d/TB[b%d];",
                        i + 1, dims.width, dims.height, dims.width, dims.height, start, i + 1));
            }

            String currentBase = "[v0]";
            for (int i = 0; i < brolls.size(); i++) {
                ContentSceneEntity sc = brolls.get(i);
                int start = sc.getStartTimeSeconds() != null ? sc.getStartTimeSeconds() : (10 + i * 15);
                int end = start + (sc.getDurationSeconds() != null ? sc.getDurationSeconds() : 5);
                String nextOut = "[v" + (i + 1) + "]";
                filter.append(String.format("%s[b%d]overlay=0:0:enable='between(t,%d,%d)':eof_action=repeat%s;",
                        currentBase, i + 1, start, end, nextOut));
                currentBase = nextOut;
            }

            String preCaptionPad = currentBase;
            if (logoInputIdx != -1) {
                preCaptionPad = "[v_logo]";
                filter.append(String.format("[%d:v]scale=%d:-1[logo_scaled];", logoInputIdx, dims.logoWidth));
                filter.append(String.format("%s[logo_scaled]overlay=main_w-overlay_w-%d:%d:format=auto%s;",
                        currentBase, dims.logoX, dims.logoY, preCaptionPad));
            }

            if (hasAssCaptions) {
                // pb.directory is tempDir, so 'ass=captions.ass' avoids Windows drive-colon escaping issues
                filter.append(String.format("%sass=captions.ass[outv]", preCaptionPad));
            } else {
                filter.append(String.format("%snull[outv]", preCaptionPad));
            }

            cmd.add("-filter_complex");
            cmd.add(filter.toString());
            cmd.add("-map");
            cmd.add("[outv]");
            cmd.add("-map");
            cmd.add("0:a");
            cmd.add("-c:v");
            cmd.add("libx264");
            cmd.add("-preset");
            cmd.add("fast");
            cmd.add("-pix_fmt");
            cmd.add("yuv420p");
            cmd.add("-c:a");
            cmd.add("aac");
            cmd.add("-shortest");
            cmd.add(outputFile.toAbsolutePath().toString());

            log.info("Running FFmpeg composite command for post {}: {}", postId, String.join(" ", cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(tempDir.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(logFile.toFile());
            proc = pb.start();
            boolean finished = proc.waitFor(180, TimeUnit.SECONDS);

            if (!finished) {
                log.warn("FFmpeg composite process timed out after 180s for post {}. Destroying process forcibly.", postId);
                proc.destroyForcibly();
            }

            if (finished && proc.exitValue() == 0 && Files.exists(outputFile) && Files.size(outputFile) > 0) {
                byte[] compBytes = Files.readAllBytes(outputFile);
                String s3Key = "media/renders/" + UUID.randomUUID() + ".mp4";
                storageService.putS3Object(s3Key, compBytes, "video/mp4");
                log.info("FFmpeg successfully composed multi-scene Reel for post {} and uploaded to S3: {}", postId, s3Key);
                return storageService.buildS3Url(s3Key);
            } else {
                String errorLog = Files.exists(logFile) ? Files.readString(logFile) : "No log file found";
                log.warn("FFmpeg composite exited with code {}, falling back to avatar URL. Error log: {}",
                        finished ? proc.exitValue() : -1,
                        errorLog.length() > 600 ? errorLog.substring(errorLog.length() - 600) : errorLog);
                return avatarUrl;
            }
        } finally {
            if (proc != null && proc.isAlive()) {
                proc.destroyForcibly();
            }
            try {
                Files.walk(tempDir)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }

    private void extractAudioForCaptions(String ffmpegBin, Path videoFile, Path audioOut) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegBin, "-y", "-i", videoFile.toAbsolutePath().toString(),
                    "-vn", "-acodec", "libmp3lame", "-ar", "16000", "-ac", "1", "-b:a", "64k",
                    audioOut.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor(45, TimeUnit.SECONDS);
            if (p.isAlive()) p.destroyForcibly();
        } catch (Exception e) {
            log.warn("Failed extracting audio for captions: {}", e.getMessage());
        }
    }

    private List<CaptionCue> buildCaptionCues(Long postId, Path audioFile, int totalDurationSeconds,
                                             String spokenScript, String captionLanguage) {
        List<CaptionCue> cues = new ArrayList<>();

        if (isRealKey(openAiKey) && Files.exists(audioFile)) {
            try {
                if (Files.size(audioFile) > 512) {
                    cues = transcribeAudioWithWhisper(postId, audioFile, spokenScript, totalDurationSeconds);
                }
            } catch (Exception e) {
                log.warn("Whisper transcription failed for post {}, falling back to script timing: {}", postId, e.getMessage());
            }
        }

        if (cues.isEmpty() && spokenScript != null && !spokenScript.isBlank()) {
            cues = buildEstimatedCuesFromScript(spokenScript, totalDurationSeconds);
        }

        if (!cues.isEmpty() && isRealKey(openAiKey)) {
            cues = adaptCaptionsToLanguage(cues, captionLanguage, spokenScript);
        }

        return cues;
    }

    private List<CaptionCue> transcribeAudioWithWhisper(Long postId, Path audioFile, String promptHint,
                                                        int fallbackDurationSec) throws Exception {
        byte[] audioBytes = Files.readAllBytes(audioFile);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(openAiKey);

        org.springframework.util.MultiValueMap<String, Object> form = new org.springframework.util.LinkedMultiValueMap<>();
        org.springframework.core.io.ByteArrayResource fileResource = new org.springframework.core.io.ByteArrayResource(audioBytes) {
            @Override
            public String getFilename() {
                return "caption_audio.mp3";
            }
        };
        form.add("file", fileResource);
        form.add("model", "whisper-1");
        form.add("response_format", "verbose_json");
        form.add("timestamp_granularities[]", "word");
        form.add("timestamp_granularities[]", "segment");
        if (promptHint != null && !promptHint.isBlank()) {
            String trimmedPrompt = promptHint.length() > 300 ? promptHint.substring(0, 300) : promptHint;
            form.add("prompt", trimmedPrompt);
        }

        HttpEntity<org.springframework.util.MultiValueMap<String, Object>> entity = new HttpEntity<>(form, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(openAiBaseUrl + "/audio/transcriptions", entity, String.class);

        JsonNode root = objectMapper.readTree(response.getBody());
        double audioDuration = root.has("duration") ? root.get("duration").asDouble(fallbackDurationSec) : fallbackDurationSec;
        recordUsage(postId, "OPENAI", "whisper-1", Math.max(0.1, audioDuration / 60.0), "MINUTES", Math.max(0.001, (audioDuration / 60.0) * 0.006));

        List<CaptionCue> result = new ArrayList<>();
        JsonNode wordsNode = root.get("words");
        if (wordsNode != null && wordsNode.isArray() && !wordsNode.isEmpty()) {
            StringBuilder buf = new StringBuilder();
            double cueStart = -1;
            double cueEnd = 0;
            int wordCount = 0;

            for (int i = 0; i < wordsNode.size(); i++) {
                JsonNode w = wordsNode.get(i);
                String wordText = w.path("word").asText("").trim();
                if (wordText.isEmpty()) continue;

                double wStart = w.path("start").asDouble(cueEnd);
                double wEnd = w.path("end").asDouble(wStart + 0.25);

                // Break on a noticeable pause (> 0.45s) before adding the new word
                if (wordCount > 0 && (wStart - cueEnd) > 0.45) {
                    result.add(new CaptionCue(cueStart, Math.max(cueEnd, cueStart + 0.35), buf.toString().trim()));
                    buf.setLength(0);
                    wordCount = 0;
                    cueStart = -1;
                }

                if (cueStart < 0) cueStart = wStart;
                if (buf.length() > 0) buf.append(" ");
                buf.append(wordText);
                cueEnd = wEnd;
                wordCount++;

                boolean endsClause = wordText.endsWith(".") || wordText.endsWith("!") || wordText.endsWith("?")
                        || wordText.endsWith(",") || wordText.endsWith(";") || wordText.endsWith(":") || wordText.endsWith("à¥¤");

                if (wordCount >= 4 || (wordCount >= 2 && endsClause) || buf.length() >= 24) {
                    result.add(new CaptionCue(cueStart, Math.max(cueEnd, cueStart + 0.35), buf.toString().trim()));
                    buf.setLength(0);
                    wordCount = 0;
                    cueStart = -1;
                }
            }
            if (wordCount > 0 && buf.length() > 0) {
                result.add(new CaptionCue(cueStart, Math.max(cueEnd, cueStart + 0.35), buf.toString().trim()));
            }
            return result;
        }

        // Fallback to segment-level timestamps if words array wasn't returned
        JsonNode segsNode = root.get("segments");
        if (segsNode != null && segsNode.isArray()) {
            for (JsonNode seg : segsNode) {
                String segText = seg.path("text").asText("").trim();
                if (segText.isEmpty()) continue;
                double sStart = seg.path("start").asDouble(0.0);
                double sEnd = seg.path("end").asDouble(sStart + 2.0);
                result.addAll(splitTextIntoTimedCues(segText, sStart, sEnd));
            }
        }
        return result;
    }

    private List<CaptionCue> buildEstimatedCuesFromScript(String spokenScript, int totalDurationSeconds) {
        double safeDuration = Math.max(4.0, totalDurationSeconds - 0.5);
        return splitTextIntoTimedCues(spokenScript, 0.2, safeDuration);
    }

    private List<CaptionCue> splitTextIntoTimedCues(String text, double startSec, double endSec) {
        List<CaptionCue> cues = new ArrayList<>();
        String[] words = text.trim().split("\\s+");
        if (words.length == 0) return cues;

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int count = 0;
        for (String w : words) {
            if (current.length() > 0) current.append(" ");
            current.append(w);
            count++;
            boolean endsClause = w.endsWith(".") || w.endsWith("!") || w.endsWith("?") || w.endsWith(",") || w.endsWith("à¥¤");
            if (count >= 4 || (count >= 2 && endsClause) || current.length() >= 24) {
                chunks.add(current.toString().trim());
                current.setLength(0);
                count = 0;
            }
        }
        if (current.length() > 0) chunks.add(current.toString().trim());

        int totalChars = chunks.stream().mapToInt(String::length).sum();
        if (totalChars <= 0) totalChars = 1;
        double span = Math.max(0.5, endSec - startSec);
        double cursor = startSec;

        for (String chunk : chunks) {
            double chunkDur = span * ((double) chunk.length() / totalChars);
            chunkDur = Math.max(0.4, chunkDur);
            double cEnd = Math.min(endSec + 0.5, cursor + chunkDur);
            cues.add(new CaptionCue(cursor, cEnd, chunk));
            cursor = cEnd;
        }
        return cues;
    }

    private List<CaptionCue> adaptCaptionsToLanguage(List<CaptionCue> cues, String captionLanguage, String spokenScript) {
        try {
            String lang = (captionLanguage != null && !captionLanguage.isBlank()) ? captionLanguage.trim() : "Hinglish";
            String langInstruction;
            if (lang.equalsIgnoreCase("Hindi")) {
                langInstruction = "Convert every cue into clear, natural Hindi written in Devanagari script (à¤¹à¤¿à¤¨à¥à¤¦à¥€). Keep globally recognized abbreviations/brand names readable (or transliterated cleanly where natural), and make sure every chunk flows smoothly.";
            } else if (lang.equalsIgnoreCase("English")) {
                langInstruction = "Translate/refine every cue into crisp, natural English suitable for Instagram Reels captions.";
            } else {
                langInstruction = "Write every cue in natural Romanized Hinglish (Latin letters only, NO Devanagari characters), matching the exact spoken Hinglish words and fixing any misheard brand or medical spelling using the Reference Script.";
            }

            List<Map<String, Object>> inputCues = new ArrayList<>();
            for (int i = 0; i < cues.size(); i++) {
                inputCues.add(Map.of("id", i, "text", cues.get(i).text()));
            }

            String prompt = String.format("""
                You are an expert Instagram Reels subtitle editor.
                Target Caption Language & Script: %s
                Rule: %s

                Reference Spoken Script (use this for exact spelling of brand names, medical/technical terms, and intent):
                %s

                Timed Caption Cues JSON:
                %s

                Instructions:
                - Return the EXACT same number of cues with matching "id" integers (0 to %d).
                - Keep each cue concise (short 3-5 words matching its audio beat) so it stays strictly synced to audio timing.
                - Output ONLY valid JSON in this schema:
                {
                  "cues": [
                    { "id": 0, "text": "..." }
                  ]
                }
                """,
                    lang,
                    langInstruction,
                    (spokenScript != null && !spokenScript.isBlank()) ? spokenScript : "N/A",
                    objectMapper.writeValueAsString(inputCues),
                    cues.size() - 1
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openAiKey);

            Map<String, Object> body = new HashMap<>();
            body.put("model", openAiModel);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", "You refine timed video subtitle cues into the exact target script/language and return valid JSON."),
                    Map.of("role", "user", "content", prompt)
            ));
            body.put("response_format", Map.of("type", "json_object"));

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(openAiBaseUrl + "/chat/completions", entity, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            String content = root.path("choices").get(0).path("message").path("content").asText();
            JsonNode outJson = objectMapper.readTree(content);

            Map<Integer, String> updatedById = new HashMap<>();
            if (outJson.has("cues") && outJson.get("cues").isArray()) {
                for (JsonNode c : outJson.get("cues")) {
                    int id = c.path("id").asInt(-1);
                    String txt = c.path("text").asText("").trim();
                    if (id >= 0 && !txt.isEmpty()) {
                        updatedById.put(id, txt);
                    }
                }
            }

            List<CaptionCue> adapted = new ArrayList<>(cues.size());
            for (int i = 0; i < cues.size(); i++) {
                CaptionCue orig = cues.get(i);
                String newText = updatedById.getOrDefault(i, orig.text());
                adapted.add(new CaptionCue(orig.startSec(), orig.endSec(), newText));
            }
            return adapted;
        } catch (Exception e) {
            log.warn("Caption language adaptation failed, keeping transcribed cues: {}", e.getMessage());
            return cues;
        }
    }

    private String buildAssSubtitles(List<CaptionCue> cues, VideoDimensions dims) {
        StringBuilder ass = new StringBuilder();
        ass.append("[Script Info]\n");
        ass.append("ScriptType: v4.00+\n");
        ass.append("PlayResX: ").append(dims.width).append("\n");
        ass.append("PlayResY: ").append(dims.height).append("\n");
        ass.append("WrapStyle: 0\n");
        ass.append("ScaledBorderAndShadow: yes\n\n");

        ass.append("[V4+ Styles]\n");
        ass.append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n");
        ass.append(String.format(
                "Style: Default,Nirmala UI,%d,&H00FFFFFF,&H0000FFFF,&H00000000,&H99000000,-1,0,0,0,100,100,0,0,1,5,2,2,90,90,%d,1\n\n",
                dims.captionFontSize, dims.captionMarginV));

        ass.append("[Events]\n");
        ass.append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");

        double prevEnd = 0.0;
        for (CaptionCue cue : cues) {
            if (cue.text() == null || cue.text().isBlank()) continue;
            double start = Math.max(prevEnd, cue.startSec());
            double end = Math.max(start + 0.35, cue.endSec());
            prevEnd = end;

            String formattedText = wrapAssCaptionLine(cue.text())
                    .replace("{", "(")
                    .replace("}", ")");

            ass.append(String.format(Locale.US, "Dialogue: 0,%s,%s,Default,,0,0,0,,%s\n",
                    formatAssTimestamp(start),
                    formatAssTimestamp(end),
                    formattedText));
        }
        return ass.toString();
    }

    private String wrapAssCaptionLine(String text) {
        String clean = text.trim().replaceAll("\\s+", " ");
        if (clean.length() <= 24) return clean;

        int mid = clean.length() / 2;
        int bestSplit = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < clean.length(); i++) {
            if (clean.charAt(i) == ' ') {
                int dist = Math.abs(i - mid);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestSplit = i;
                }
            }
        }
        if (bestSplit > 0 && bestSplit < clean.length() - 1) {
            return clean.substring(0, bestSplit).trim() + "\\N" + clean.substring(bestSplit + 1).trim();
        }
        return clean;
    }

    private String formatAssTimestamp(double seconds) {
        double clamped = Math.max(0.0, seconds);
        int totalCentis = (int) Math.round(clamped * 100.0);
        int cs = totalCentis % 100;
        int totalSecs = totalCentis / 100;
        int s = totalSecs % 60;
        int totalMins = totalSecs / 60;
        int m = totalMins % 60;
        int h = totalMins / 60;
        return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs);
    }

    public void downloadFile(String url, Path destination) throws IOException {
        String s3Key = storageService.extractS3Key(url);
        if (s3Key != null && !s3Key.startsWith("http")) {
            try {
                byte[] bytes = storageService.getS3Object(s3Key);
                if (bytes != null && bytes.length > 0) {
                    Files.write(destination, bytes);
                    return;
                }
            } catch (Exception e) {
                log.debug("Direct S3 object fetch failed for {}, falling back to presigned/HTTP: {}", s3Key, e.getMessage());
            }
        }
        String signedUrl = storageService.getPresignedUrl(url, Duration.ofMinutes(15));
        if (signedUrl != null && !signedUrl.startsWith("http")) {
            if (s3Key != null) {
                byte[] bytes = storageService.getS3Object(s3Key);
                if (bytes != null && bytes.length > 0) {
                    Files.write(destination, bytes);
                    return;
                }
            }
        }
        byte[] bytes = restTemplate.getForObject(signedUrl, byte[].class);
        if (bytes != null) {
            Files.write(destination, bytes);
        }
    }

    // =========================================================================
    // 6. META INSTAGRAM & FACEBOOK GRAPH API PUBLISHING
    // =========================================================================
    private String getEffectiveMetaAccessToken() {
        if (masterSettingsService != null) {
            try {
                Map<String, Object> s = masterSettingsService.getAllSettings();
                String token = (String) s.get("facebookPageAccessToken");
                if (token != null && !token.isBlank() && isRealKey(token)) {
                    return token.trim();
                }
                token = (String) s.get("metaAccessToken");
                if (token != null && !token.isBlank() && isRealKey(token)) {
                    return token.trim();
                }
            } catch (Exception ignored) {}
        }
        return metaAccessToken;
    }

    private String getEffectiveInstagramAccountId() {
        if (masterSettingsService != null) {
            try {
                Map<String, Object> s = masterSettingsService.getAllSettings();
                String id = (String) s.get("instagramAccountId");
                if (id != null && !id.isBlank() && isRealKey(id)) {
                    return id.trim();
                }
            } catch (Exception ignored) {}
        }
        return metaAccountId;
    }

    public Map<String, String> publishToInstagram(Long postId, String videoUrl, String caption) {
        return publishToInstagram(postId, videoUrl, null, caption);
    }

    public Map<String, String> publishToInstagram(Long postId, String videoUrl, String coverImageUrl, String caption) {
        log.info("Publishing Reel to Instagram for post {}...", postId);
        MediaJobEntity job = recordJob(postId, null, JobProvider.META, "PUBLISH_INSTAGRAM");

        String containerId = "ig_container_" + System.currentTimeMillis();
        String mediaId = "ig_media_" + System.currentTimeMillis();
        String permalink = "https://instagram.com/reel/ozo_" + postId + "_" + UUID.randomUUID().toString().substring(0, 8);

        String effectiveToken = getEffectiveMetaAccessToken();
        String effectiveAccountId = getEffectiveInstagramAccountId();

        if (isRealKey(effectiveToken) && isRealKey(effectiveAccountId)) {
            try {
                // 1. Create media container with on-demand presigned video URL
                String externalVideoUrl = storageService.getPresignedUrl(videoUrl, Duration.ofHours(24));
                String url = String.format("https://graph.facebook.com/%s/%s/media", metaGraphVersion, effectiveAccountId);
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);

                Map<String, Object> body = new HashMap<>();
                body.put("media_type", "REELS");
                body.put("video_url", externalVideoUrl);
                body.put("caption", caption != null ? caption : "");
                body.put("share_to_feed", true);
                body.put("access_token", effectiveToken);

                if (coverImageUrl != null && !coverImageUrl.isBlank()) {
                    String externalCoverUrl = storageService.getPresignedUrl(coverImageUrl, Duration.ofHours(24));
                    body.put("cover_url", externalCoverUrl);
                }

                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
                ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
                JsonNode json = objectMapper.readTree(response.getBody());
                if (json.has("id")) {
                    containerId = json.get("id").asText();
                    log.info("Meta container created with ID: {}", containerId);

                    // 2. Poll container status until FINISHED
                    boolean isReady = pollMetaContainerReady(containerId, effectiveToken, 60);

                    // 3. Publish container to Instagram
                    if (isReady) {
                        String publishUrl = String.format("https://graph.facebook.com/%s/%s/media_publish?creation_id=%s&access_token=%s",
                                metaGraphVersion, effectiveAccountId, containerId, effectiveToken);
                        ResponseEntity<String> pubResponse = restTemplate.postForEntity(publishUrl, null, String.class);
                        JsonNode pubJson = objectMapper.readTree(pubResponse.getBody());
                        if (pubJson.has("id")) {
                            mediaId = pubJson.get("id").asText();
                            log.info("Meta Reel successfully published! Media ID: {}", mediaId);

                            // 4. Fetch live permalink
                            try {
                                String permalinkUrl = String.format("https://graph.facebook.com/%s/%s?fields=permalink&access_token=%s",
                                        metaGraphVersion, mediaId, effectiveToken);
                                ResponseEntity<String> permResponse = restTemplate.getForEntity(permalinkUrl, String.class);
                                JsonNode permJson = objectMapper.readTree(permResponse.getBody());
                                if (permJson.has("permalink")) {
                                    permalink = permJson.get("permalink").asText();
                                }
                            } catch (Exception permEx) {
                                log.debug("Could not fetch live permalink: {}", permEx.getMessage());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Meta publishing error: {}", e.getMessage());
            }
        }

        completeJob(job, 0.0);
        return Map.of("containerId", containerId, "mediaId", mediaId, "permalink", permalink);
    }

    public Map<String, String> publishToFacebookPage(Long postId, String mediaUrl, String caption, boolean isVideo) {
        log.info("Publishing to Facebook Page for post {} (isVideo={})...", postId, isVideo);
        MediaJobEntity job = recordJob(postId, null, JobProvider.META, "PUBLISH_FACEBOOK_PAGE");
        Map<String, String> result = metaIntegrationService.publishToFacebookPage(postId, mediaUrl, caption, isVideo);
        completeJob(job, 0.0);
        return result;
    }

    private boolean pollMetaContainerReady(String containerId, int maxWaitSeconds) {
        return pollMetaContainerReady(containerId, getEffectiveMetaAccessToken(), maxWaitSeconds);
    }

    private boolean pollMetaContainerReady(String containerId, String token, int maxWaitSeconds) {
        long startTime = System.currentTimeMillis();
        long maxWaitMillis = maxWaitSeconds * 1000L;
        while (System.currentTimeMillis() - startTime < maxWaitMillis) {
            try {
                String statusUrl = String.format("https://graph.facebook.com/%s/%s?fields=status_code,status&access_token=%s",
                        metaGraphVersion, containerId, token);
                ResponseEntity<String> response = restTemplate.getForEntity(statusUrl, String.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    JsonNode root = objectMapper.readTree(response.getBody());
                    String statusCode = root.path("status_code").asText();
                    if ("FINISHED".equalsIgnoreCase(statusCode)) {
                        log.info("Meta container {} video ingestion finished. Ready for publishing.", containerId);
                        return true;
                    } else if ("ERROR".equalsIgnoreCase(statusCode)) {
                        log.error("Meta container {} encountered ingestion error", containerId);
                        return false;
                    }
                }
            } catch (Exception e) {
                log.debug("Meta status poll notice: {}", e.getMessage());
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {}
        }
        log.warn("Meta container {} status polling timed out after {}s", containerId, maxWaitSeconds);
        return false;
    }

    private MediaJobEntity recordJob(Long postId, Long sceneId, JobProvider provider, String operation) {
        MediaJobEntity entity = MediaJobEntity.builder()
                .postId(postId)
                .sceneId(sceneId)
                .provider(provider)
                .operation(operation)
                .providerTaskId("task_" + UUID.randomUUID().toString().substring(0, 8))
                .status(JobStatus.IN_PROGRESS)
                .startedAt(LocalDateTime.now())
                .retries(0)
                .costUsd(0.0)
                .build();
        return mediaJobRepository.save(entity);
    }

    private void completeJob(MediaJobEntity job, double cost) {
        job.setStatus(JobStatus.COMPLETED);
        job.setCompletedAt(LocalDateTime.now());
        job.setCostUsd(cost);
        mediaJobRepository.save(job);
    }

    private void recordUsage(Long postId, String provider, String model, double units, String unitType, double cost) {
        usageRepository.save(GenerationUsageEntity.builder()
                .postId(postId)
                .provider(provider)
                .model(model)
                .units(units)
                .unitType(unitType)
                .estimatedCost(cost)
                .actualCost(cost)
                .currency("USD")
                .createdAt(LocalDateTime.now())
                .build());
    }
}

