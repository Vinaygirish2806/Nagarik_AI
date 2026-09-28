package com.nagarik.legal;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/legal")
public class LegalAskController {

    private static final Logger logger = LoggerFactory.getLogger(LegalAskController.class);
    private static final String MODEL = "gemini-2.5-flash";
    private static final int MAX_QUESTION_LENGTH = 10_000;
    private static final String DISCLAIMER = "**Disclaimer: This information is for general educational purposes only and does not constitute formal legal advice. Please consult a licensed professional in your specific jurisdiction for direct legal counsel.**";
    private static final String SYSTEM_INSTRUCTION = "You are a professional legal information assistant. Answer general legal questions clearly, factually, and objectively. You are an educational tool, NOT an attorney. You cannot provide formal legal advice. You MUST conclude every response with this exact bold statement: " + DISCLAIMER;

    @PostMapping(
        path = "/ask",
        consumes = MediaType.TEXT_PLAIN_VALUE,
        produces = MediaType.TEXT_PLAIN_VALUE
    )
    public ResponseEntity<String> ask(@RequestBody(required = false) String question) {
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body("Please provide a legal question.");
        }
        if (question.length() > MAX_QUESTION_LENGTH) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body("The question must be 10,000 characters or fewer.");
        }

        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body("The legal AI service is not configured. Set GEMINI_API_KEY on the server.");
        }

        try (Client client = Client.builder().apiKey(apiKey).build()) {
            Content systemInstruction = Content.fromParts(Part.fromText(SYSTEM_INSTRUCTION));
            GenerateContentConfig config = GenerateContentConfig.builder()
                .systemInstruction(systemInstruction)
                .build();

            GenerateContentResponse response = client.models.generateContent(MODEL, question, config);
            String answer = response.text();
            if (answer == null || answer.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("The AI service returned an empty response. Please try again.");
            }

            return ResponseEntity.ok(appendDisclaimer(answer));
        } catch (Exception exception) {
            logger.error("Gemini legal answer generation failed ({})", exception.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body("The AI service could not answer right now. Please try again later.");
        }
    }

    private String appendDisclaimer(String answer) {
        String cleanedAnswer = answer.strip();
        if (cleanedAnswer.endsWith(DISCLAIMER)) {
            return cleanedAnswer;
        }
        return cleanedAnswer + "\n\n" + DISCLAIMER;
    }
}