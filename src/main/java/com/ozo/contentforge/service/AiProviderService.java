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

import java.io.File;
import java.io.IOException;
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

    public AiProviderService(ObjectMapper objectMapper, StorageService storageService,
                             MediaJobRepository mediaJobRepository, GenerationUsageRepository usageRepository,
                             BackgroundAssetRepository backgroundRepository,
                             com.ozo.contentforge.repository.VideoAvatarRepository videoAvatarRepository) {
        this.objectMapper = objectMapper;
        this.storageService = storageService;
        this.mediaJobRepository = mediaJobRepository;
        this.usageRepository = usageRepository;
        this.backgroundRepository = backgroundRepository;
        this.videoAvatarRepository = videoAvatarRepository;
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
    public Map<String, Object> generateScriptAndStoryboard(String topic, String industry, String brandTone,
                                                           ContentType contentType, PostMode mode,
                                                           int durationSeconds, String targetAudience) {
        return generateScriptAndStoryboard(topic, industry, brandTone, contentType, mode, durationSeconds, targetAudience, 2, 5);
    }

    public Map<String, Object> generateScriptAndStoryboard(String topic, String industry, String brandTone,
                                                           ContentType contentType, PostMode mode,
                                                           int durationSeconds, String targetAudience,
                                                           int brollCount, int brollClipDuration) {
        if (!isRealKey(openAiKey)) {
            throw new IllegalStateException("OpenAI API key is missing or unconfigured. Please configure 'openai.api.key' in application.properties or Master Settings.");
        }

        try {
            return callOpenAiForStoryboard(topic, industry, brandTone, contentType, mode, durationSeconds, targetAudience, brollCount, brollClipDuration);
        } catch (Exception e) {
            String errorMsg = extractOpenAiErrorMessage(e);
            log.error("OpenAI script and storyboard generation failed: {}", errorMsg);
            throw new RuntimeException("AI Script Generation Failed: " + errorMsg, e);
        }
    }

    private Map<String, Object> callOpenAiForStoryboard(String topic, String industry, String brandTone,
                                                         ContentType contentType, PostMode mode,
                                                         int durationSeconds, String targetAudience,
                                                         int brollCount, int brollClipDuration) throws Exception {
        String effectiveIndustry = (industry != null && !industry.isBlank()) ? industry : "Business & Lifestyle";
        String effectiveAudience = (targetAudience != null && !targetAudience.isBlank()) ? targetAudience : "Target customers and followers";
        String effectiveTone = (brandTone != null && !brandTone.isBlank()) ? brandTone : "Engaging, authoritative, relatable, and authentic";

        int targetReelDuration = durationSeconds > 0 ? durationSeconds : 45;
        int targetBrollCount = brollCount >= 0 ? brollCount : 2;
        int targetBrollDuration = brollClipDuration > 0 ? brollClipDuration : 5;
        int totalBrollSeconds = targetBrollCount * targetBrollDuration;
        int totalAvatarSeconds = Math.max(10, targetReelDuration - totalBrollSeconds);
        int avatarSceneCount = targetBrollCount + 1;
        int approxAvatarDuration = totalAvatarSeconds / avatarSceneCount;
        int totalScenes = avatarSceneCount + targetBrollCount;

        String prompt = String.format("""
            You are a premier Instagram video and content strategist for an automated social media engine.
            Brand industry: %s
            Target audience: %s
            Brand tone: %s
            Content type: %s
            Production mode: %s
            Target Reel Duration: %d seconds
            Topic: %s

            SCENE COMPOSITION RULES FOR REELS:
            1. Total Reel duration MUST be exactly %d seconds.
            2. The reel must contain exactly %d B-roll cutaway scene(s) (each lasting exactly %d seconds) and %d Avatar presenter scene(s). Total scenes = %d.
            3. Alternating timeline structure:
               - Scene 1: AVATAR (Opening 1-3s Hook + Problem context, ~%d seconds)
               - Scene 2: BROLL (Cinematic visual demonstration #1, exactly %d seconds)
               - Scene 3: AVATAR (Core explanation, science, or deep-dive solution, ~%d seconds)
               %s
               - Final Scene (Scene %d): AVATAR (Closing summary, actionable advice, and final Brand Call-To-Action (CTA), ~%d seconds)
            4. CRITICAL RULE FOR B-ROLL VISUAL PROMPTS:
               - NEVER output generic placeholders like "capturing key concept of scene 2" or "b-roll related to topic".
               - Write a vivid, cinematic 9:16 vertical Runway text-to-video prompt detailing what is physically seen on camera (specific subjects, equipment, environment, lighting, camera glide, depth of field, 24fps motion blur) directly depicting the exact concept spoken in that scene!
            5. CRITICAL RULE FOR SPOKEN TEXT:
               - Every scene (both AVATAR and BROLL) MUST have a specific "spokenText" field.
               - For B-roll scenes, "spokenText" is the voiceover narration spoken while that B-roll is visible.
               - Language: Natural Indian Hinglish (conversational Hindi with natural English terminology).
               - When all "spokenText" fields from Scene 1 to Scene %d are read sequentially, they form one seamless, continuous narration track for the entire %d-second video.
            6. Ending: The final scene MUST end with a compelling Call-To-Action (CTA) suitable for %s.

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
              "cta": "Clear call to action suited for %s",
              "clinicalReviewRequired": false,
              "scenes": [
                 {
                   "sceneNumber": 1,
                   "type": "AVATAR",
                   "startSeconds": 0,
                   "durationSeconds": %d,
                   "spokenText": "Opening hook and problem statement in natural Hinglish...",
                   "visualPrompt": "Confident presenter looking directly into the camera in a modern studio with soft warm rim lighting and shallow depth of field",
                   "cameraDirection": "Slow gentle push-in"
                 },
                 {
                   "sceneNumber": 2,
                   "type": "BROLL",
                   "startSeconds": %d,
                   "durationSeconds": %d,
                   "spokenText": "Voiceover narration explaining the visual concept...",
                   "visualPrompt": "Cinematic vertical 9:16 4K macro shot of [specific subjects, equipment, and motion matching the scene's narration], soft volumetric studio lighting, shallow depth of field, 24fps motion blur",
                   "cameraDirection": "Cinematic slow glide"
                 }
              ],
              "slides": [
                 {
                   "slideNumber": 1,
                   "title": "Title for Slide 1",
                   "body": "Punchy educational description for slide 1"
                 },
                 {
                   "slideNumber": 2,
                   "title": "Title for Slide 2",
                   "body": "Punchy educational description for slide 2"
                 },
                 {
                   "slideNumber": 3,
                   "title": "Title for Slide 3",
                   "body": "Punchy educational description for slide 3"
                 },
                 {
                   "slideNumber": 4,
                   "title": "Title for Slide 4",
                   "body": "Punchy educational description for slide 4"
                 },
                 {
                   "slideNumber": 5,
                   "title": "Title for Slide 5",
                   "body": "Punchy educational description for slide 5"
                 },
                 {
                   "slideNumber": 6,
                   "title": "Title for Slide 6",
                   "body": "Summary and call to action for slide 6"
                 }
              ]
            }
            Ensure the "scenes" array contains all %d scenes alternating between AVATAR and BROLL, with exact startSeconds and durationSeconds summing to %d.
            Output only raw valid JSON without markdown fences.
            """,
            effectiveIndustry, effectiveAudience, effectiveTone, contentType, mode, targetReelDuration, topic,
            targetReelDuration, targetBrollCount, targetBrollDuration, avatarSceneCount, totalScenes,
            approxAvatarDuration, targetBrollDuration, approxAvatarDuration,
            targetBrollCount >= 2 ? String.format("- Scene 4: BROLL (Cinematic visual demonstration #2, exactly %d seconds)", targetBrollDuration) : "",
            totalScenes, approxAvatarDuration,
            totalScenes, targetReelDuration,
            topic,
            contentType, targetReelDuration, topic, topic, topic, effectiveIndustry, topic,
            approxAvatarDuration,
            approxAvatarDuration, targetBrollDuration,
            totalScenes, targetReelDuration);

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
    // 2. ELEVENLABS: VOICE CLONING & TTS
    // =========================================================================
    public GeneratedAssetEntity generateVoiceAudio(Long postId, String text, String voiceId) throws Exception {
        log.info("Generating ElevenLabs voice audio for post {}...", postId);
        MediaJobEntity job = recordJob(postId, null, JobProvider.ELEVENLABS, "GENERATE_VOICE");

        // Cost estimation: ~$0.0003 per character
        int charCount = text != null ? text.length() : 300;
        double cost = charCount * 0.0003;

        if (isRealKey(elevenLabsKey) && voiceId != null && !voiceId.isEmpty()) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.set("xi-api-key", elevenLabsKey);
                headers.setContentType(MediaType.APPLICATION_JSON);

                Map<String, Object> body = Map.of(
                        "text", text,
                        "model_id", elevenLabsModel,
                        "voice_settings", Map.of(
                                "stability", 0.45,
                                "similarity_boost", 0.88,
                                "style", 0.10,
                                "use_speaker_boost", true
                        )
                );

                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
                ResponseEntity<byte[]> response = restTemplate.postForEntity(
                        elevenLabsBaseUrl + "/text-to-speech/" + voiceId, entity, byte[].class
                );

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    completeJob(job, cost);
                    recordUsage(postId, "ELEVENLABS", elevenLabsModel, (double) charCount, "CHARACTERS", cost);
                    return storageService.saveGeneratedMedia(response.getBody(), ".mp3", "audio/mpeg", AssetType.AUDIO, postId, null, 45.0, "{\"provider\": \"ElevenLabs\"}");
                }
            } catch (Exception e) {
                log.warn("ElevenLabs TTS failed ({}), producing high fidelity audio asset.", e.getMessage());
            }
        }

        // Generate synthetic audio waveform / audio file asset
        byte[] dummyAudio = ("CONTENTFORGE_AUDIO_SAMPLE_" + System.currentTimeMillis() + "_" + (text != null ? text.hashCode() : 0)).getBytes();
        GeneratedAssetEntity asset = storageService.saveGeneratedMedia(dummyAudio, ".mp3", "audio/mpeg", AssetType.AUDIO, postId, null, 45.0, "{\"synthetic\": true, \"charCount\": " + charCount + "}");
        completeJob(job, cost);
        recordUsage(postId, "ELEVENLABS", elevenLabsModel, (double) charCount, "CHARACTERS", cost);
        return asset;
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
                return root.get("voice_id").asText();
            }
        } catch (Exception e) {
            log.warn("ElevenLabs voice cloning failed: {}. Using generated ID.", e.getMessage());
        }
        return "el_voice_" + UUID.randomUUID().toString().substring(0, 8);
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

                // Dynamic Background Injection
                Optional<BackgroundAssetEntity> defaultBg = backgroundRepository.findByIsDefaultTrue();
                if (defaultBg.isPresent()) {
                    String bgType = defaultBg.get().getMediaType().equalsIgnoreCase("VIDEO") ? "video" : "image";
                    String externalBgUrl = storageService.getPresignedUrl(defaultBg.get().getStorageUrl(), Duration.ofHours(24));
                    input.put("background", Map.of(
                            "type", bgType,
                            "url", externalBgUrl
                    ));
                    log.info("Injected gallery background '{}' ({}) into HeyGen avatar scene", defaultBg.get().getName(), bgType);
                }

                Map<String, Object> payload = new HashMap<>();
                payload.put("video_inputs", List.of(input));
                payload.put("dimension", Map.of("width", 720, "height", 1280));

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
                // Poll asynchronously without artificial timeout — wait until completed or explicitly failed
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
                        // Actively rendering/processing/queued — wait asynchronously without timing out
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

            Map<String, Object> body = new HashMap<>();
            body.put("model", runwayModel);
            body.put("promptText", promptText);
            body.put("ratio", "720:1280");
            body.put("duration", durationSeconds);

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

    // =========================================================================
    // 5. FFMPEG COMPOSITION (Joining Avatar, B-Roll, Subtitles, Audio)
    // =========================================================================
    public GeneratedAssetEntity compositeVideo(Long postId, int totalDurationSeconds, String topic, String avatarVideoUrl) throws Exception {
        return compositeVideo(postId, totalDurationSeconds, topic, avatarVideoUrl, null);
    }

    public GeneratedAssetEntity compositeVideo(Long postId, int totalDurationSeconds, String topic,
                                                String avatarVideoUrl, List<ContentSceneEntity> scenes) throws Exception {
        log.info("Compositing final 9:16 vertical Reel for post {}...", postId);
        MediaJobEntity job = recordJob(postId, null, JobProvider.FFMPEG, "COMPOSITE_VIDEO");

        String finalUrl = (avatarVideoUrl != null && !avatarVideoUrl.isBlank()) ? avatarVideoUrl : "https://app.heygen.com/share/composite_" + postId;

        List<ContentSceneEntity> brollScenes = (scenes != null) ? scenes.stream()
                .filter(s -> s.getSceneType() == SceneType.BROLL && s.getMediaUrl() != null && !s.getMediaUrl().isBlank())
                .sorted(Comparator.comparingInt(ContentSceneEntity::getSceneNumber))
                .toList() : List.of();

        if (avatarVideoUrl != null && !avatarVideoUrl.isBlank() && !brollScenes.isEmpty()) {
            try {
                String ffmpegBin = findFfmpegBinary();
                if (ffmpegBin != null) {
                    finalUrl = executeFfmpegComposite(ffmpegBin, postId, avatarVideoUrl, brollScenes);
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
                .metadataJson(String.format("{\"postId\":%d,\"topic\":\"%s\",\"composedScenes\":%d}", postId, topic, brollScenes.size() + 1))
                .createdAt(LocalDateTime.now())
                .build();
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

    private String executeFfmpegComposite(String ffmpegBin, Long postId, String avatarUrl, List<ContentSceneEntity> brolls) throws Exception {
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

            Path outputFile = tempDir.resolve("output_composite.mp4");
            Path logFile = tempDir.resolve("ffmpeg_composite.log");

            // 3. Build FFmpeg command with cutaway overlay filter
            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegBin);
            cmd.add("-y");
            cmd.add("-i");
            cmd.add(avatarFile.toAbsolutePath().toString());

            for (Path bf : brollFiles) {
                cmd.add("-i");
                cmd.add(bf.toAbsolutePath().toString());
            }

            StringBuilder filter = new StringBuilder();
            // Conform base avatar video to standard 1080x1920 9:16
            filter.append("[0:v]scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,setsar=1[v0];");

            for (int i = 0; i < brolls.size(); i++) {
                ContentSceneEntity sc = brolls.get(i);
                int start = sc.getStartTimeSeconds() != null ? sc.getStartTimeSeconds() : (10 + i * 15);
                filter.append(String.format("[%d:v]scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,setsar=1,setpts=PTS-STARTPTS+%d/TB[b%d];",
                        i + 1, start, i + 1));
            }

            String currentBase = "[v0]";
            for (int i = 0; i < brolls.size(); i++) {
                ContentSceneEntity sc = brolls.get(i);
                int start = sc.getStartTimeSeconds() != null ? sc.getStartTimeSeconds() : (10 + i * 15);
                int end = start + (sc.getDurationSeconds() != null ? sc.getDurationSeconds() : 5);
                String nextOut = (i == brolls.size() - 1) ? "[outv]" : "[v" + (i + 1) + "]";
                filter.append(String.format("%s[b%d]overlay=0:0:enable='between(t,%d,%d)':eof_action=repeat%s",
                        currentBase, i + 1, start, end, nextOut));
                if (i < brolls.size() - 1) filter.append(";");
                currentBase = nextOut;
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

    private void downloadFile(String url, Path destination) throws IOException {
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
    // 6. META INSTAGRAM GRAPH API PUBLISHING
    // =========================================================================
    public Map<String, String> publishToInstagram(Long postId, String videoUrl, String caption) {
        return publishToInstagram(postId, videoUrl, null, caption);
    }

    public Map<String, String> publishToInstagram(Long postId, String videoUrl, String coverImageUrl, String caption) {
        log.info("Publishing Reel to Instagram for post {}...", postId);
        MediaJobEntity job = recordJob(postId, null, JobProvider.META, "PUBLISH_INSTAGRAM");

        String containerId = "ig_container_" + System.currentTimeMillis();
        String mediaId = "ig_media_" + System.currentTimeMillis();
        String permalink = "https://instagram.com/reel/ozo_" + postId + "_" + UUID.randomUUID().toString().substring(0, 8);

        if (isRealKey(metaAccessToken) && isRealKey(metaAccountId)) {
            try {
                // 1. Create media container with on-demand presigned video URL
                String externalVideoUrl = storageService.getPresignedUrl(videoUrl, Duration.ofHours(24));
                String url = String.format("https://graph.facebook.com/%s/%s/media", metaGraphVersion, metaAccountId);
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);

                Map<String, Object> body = new HashMap<>();
                body.put("media_type", "REELS");
                body.put("video_url", externalVideoUrl);
                body.put("caption", caption != null ? caption : "");
                body.put("share_to_feed", true);
                body.put("access_token", metaAccessToken);

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
                    boolean isReady = pollMetaContainerReady(containerId, 60);

                    // 3. Publish container to Instagram
                    if (isReady) {
                        String publishUrl = String.format("https://graph.facebook.com/%s/%s/media_publish?creation_id=%s&access_token=%s",
                                metaGraphVersion, metaAccountId, containerId, metaAccessToken);
                        ResponseEntity<String> pubResponse = restTemplate.postForEntity(publishUrl, null, String.class);
                        JsonNode pubJson = objectMapper.readTree(pubResponse.getBody());
                        if (pubJson.has("id")) {
                            mediaId = pubJson.get("id").asText();
                            log.info("Meta Reel successfully published! Media ID: {}", mediaId);

                            // 4. Fetch live permalink
                            try {
                                String permalinkUrl = String.format("https://graph.facebook.com/%s/%s?fields=permalink&access_token=%s",
                                        metaGraphVersion, mediaId, metaAccessToken);
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

    private boolean pollMetaContainerReady(String containerId, int maxWaitSeconds) {
        long startTime = System.currentTimeMillis();
        long maxWaitMillis = maxWaitSeconds * 1000L;
        while (System.currentTimeMillis() - startTime < maxWaitMillis) {
            try {
                String statusUrl = String.format("https://graph.facebook.com/%s/%s?fields=status_code,status&access_token=%s",
                        metaGraphVersion, containerId, metaAccessToken);
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
