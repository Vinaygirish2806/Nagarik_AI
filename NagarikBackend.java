import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nagarik AI — merged Java backend + frontend host.
 * No external dependencies: uses the JDK's built-in com.sun.net.httpserver.
 *
 * Setup: put this file and index.html (the frontend) in the SAME folder.
 * Run:   javac NagarikBackend.java && java NagarikBackend
 * Then open http://localhost:8080/ — one process serves both the page and the API.
 */
public class NagarikBackend {

    private static final HttpClient AI_HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/health", new HealthHandler());
        server.createContext("/api/analyze", new AnalyzeHandler());
        server.createContext("/api/chat", new ChatHandler());
        server.createContext("/api/sources", new SourcesHandler());
        server.createContext("/", new StaticHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("Nagarik AI running on http://localhost:" + port + "  (page + API, one process)");
    }

    // ---------------- static frontend ----------------

    static class StaticHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (handlePreflight(ex)) return;
            Path file = Path.of("index.html");
            if (!Files.exists(file)) {
                file = Path.of("static", "index.html");
            }
            if (!Files.exists(file)) {
                sendJson(ex, 404, "{\"error\":\"index.html not found next to NagarikBackend.java or in static/\"}");
                return;
            }
            byte[] bytes = Files.readAllBytes(file);
            withCors(ex);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.getResponseHeaders().add("Cache-Control", "no-store, no-cache, must-revalidate");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }
    }

    // ---------------- shared helpers ----------------

    static void withCors(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        withCors(ex);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static String readBody(HttpExchange ex) throws IOException {
        InputStream is = ex.getRequestBody();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    /** Very small JSON string-field extractor — good enough for this demo's flat request bodies. */
    static String extractField(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (m.find()) {
            return decodeJsonString(m.group(1));
        }
        return "";
    }

    static String decodeJsonString(String value) {
        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current != '\\' || i + 1 >= value.length()) {
                decoded.append(current);
                continue;
            }
            char escaped = value.charAt(++i);
            switch (escaped) {
                case '"': decoded.append('"'); break;
                case '\\': decoded.append('\\'); break;
                case '/': decoded.append('/'); break;
                case 'b': decoded.append('\b'); break;
                case 'f': decoded.append('\f'); break;
                case 'n': decoded.append('\n'); break;
                case 'r': decoded.append('\r'); break;
                case 't': decoded.append('\t'); break;
                case 'u':
                    if (i + 4 < value.length()) {
                        decoded.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                    break;
                default: decoded.append(escaped);
            }
        }
        return decoded.toString();
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder escaped = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': escaped.append("\\\""); break;
                case '\\': escaped.append("\\\\"); break;
                case '\b': escaped.append("\\b"); break;
                case '\f': escaped.append("\\f"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (c < 0x20) escaped.append(String.format("\\u%04x", (int) c));
                    else escaped.append(c);
            }
        }
        return escaped.toString();
    }

    static Map<String, String> queryParams(HttpExchange ex) {
        String q = ex.getRequestURI().getRawQuery();
        Map<String, String> map = new java.util.HashMap<>();
        if (q == null) return map;
        for (String pair : q.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String val = kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            map.put(key, val);
        }
        return map;
    }

    static boolean handlePreflight(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            withCors(ex);
            ex.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    // ---------------- /api/health ----------------

    static class HealthHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (handlePreflight(ex)) return;
            sendJson(ex, 200, "{\"status\":\"ok\",\"service\":\"Nagarik AI backend\"}");
        }
    }

    // ---------------- /api/analyze ----------------

    static class AnalyzeHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (handlePreflight(ex)) return;
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 405, "{\"error\":\"POST only\"}");
                return;
            }
            String body = readBody(ex);
            String activity = extractField(body, "activity").toLowerCase();
            Assessment a = classify(activity);
            sendJson(ex, 200, a.toJson());
        }
    }

    static int countMatches(String text, String... keywords) {
        int count = 0;
        for (String kw : keywords) {
            if (text.contains(kw)) count++;
        }
        return count;
    }

    /** Keyword-based rules standing in for the AI action classifier + legal RAG engine. */
    static Assessment classify(String activity) {
        if (activity.contains("record")) {
            int m = countMatches(activity, "record", "conversation", "secret", "without", "inform", "audio", "video", "call", "consent");
            int score = Math.min(95, Math.max(60, 68 + m * 7));
            return new Assessment("yellow",
                "Recording someone without informing them can be legally risky in India depending on where the conversation happens and how the recording is used.",
                "Indian privacy jurisprudence recognises a right to privacy in conversations, especially in private spaces. Covert recording can also raise issues if the recording is later shared or used to cause harm.",
                new String[]{"📜 Information Technology Act, 2000 — S.66E", "🇮🇳 Constitution of India — Art. 21 (Right to Privacy)", "⚖️ K.S. Puttaswamy v. Union of India (2017)"},
                "Depending on context, unauthorised recording and distribution may attract civil liability or, in aggravated cases, criminal provisions relating to privacy violation.",
                "Inform the other person and obtain consent before recording, or record only where you are a participant in a public, non-private setting.",
                "Moderately supported — outcome depends on specific circumstances (location, use of recording, consent).",
                score,
                new Source[]{new Source("IT Act, 2000 — Section 66E", "Legislation"), new Source("K.S. Puttaswamy v. Union of India, (2017) 10 SCC 1", "Judgment")});
        }
        if (activity.contains("drug") || activity.contains("weed") || activity.contains("cannabis")) {
            int m = countMatches(activity, "drug", "weed", "cannabis", "narcotic", "substance", "possess", "consumption", "sell", "traffic");
            int score = Math.min(98, Math.max(75, 82 + m * 5));
            return new Assessment("red",
                "Possession, use, or sale of most narcotic and psychotropic substances is prohibited in India.",
                "The Narcotic Drugs and Psychotropic Substances (NDPS) Act criminalises possession, consumption, and trafficking of scheduled substances, with penalties scaled to quantity.",
                new String[]{"📜 Narcotic Drugs and Psychotropic Substances Act, 1985 — S.20"},
                "Penalties range from fines to rigorous imprisonment depending on the substance and quantity involved.",
                "Avoid possession or use of scheduled substances; seek legal or medical help through licensed channels where relevant.",
                "Strongly supported by statute.",
                score,
                new Source[]{new Source("NDPS Act, 1985 — Section 20", "Legislation")});
        }
        if (activity.contains("protest") || activity.contains("assembl") || activity.contains("rally")) {
            int m = countMatches(activity, "protest", "assembl", "rally", "march", "peaceful", "public", "police", "permission");
            int score = Math.min(92, Math.max(62, 70 + m * 6));
            return new Assessment("yellow",
                "Peaceful assembly is a protected right, but organising a public protest may require prior permission depending on the location and local regulations.",
                "The right to assemble peacefully is guaranteed, but is subject to reasonable restrictions such as police permissions and prohibitory orders in some areas.",
                new String[]{"🇮🇳 Constitution of India — Art. 19(1)(b)", "📜 Bharatiya Nagarik Suraksha Sanhita — provisions on public assembly"},
                "Unauthorised assembly in restricted areas can lead to detention or charges relating to unlawful assembly.",
                "Notify local police in advance and confirm whether the venue requires permission.",
                "Moderately supported — depends heavily on local orders in force.",
                score,
                new Source[]{new Source("Constitution of India, Art. 19(1)(b)", "Constitutional Provision")});
        }
        if (activity.contains("park") || activity.contains("photo") || activity.contains("public place")) {
            int m = countMatches(activity, "park", "photo", "public place", "camera", "video", "night", "civic", "shoot");
            int score = Math.min(94, Math.max(65, 74 + m * 6));
            return new Assessment("green",
                "This activity is generally permitted in public places, subject to ordinary local rules.",
                "Everyday activities in public spaces are typically lawful unless a specific local regulation, sign, or restriction says otherwise.",
                new String[]{"🏛️ Local municipal regulations (varies by city)"},
                "Minimal legal risk under normal circumstances; check for posted local restrictions.",
                "Confirm any posted signage or local municipal rules for the specific location.",
                "Generally supported by common practice; low ambiguity.",
                score,
                new Source[]{new Source("Municipal by-laws (illustrative)", "Government Notification")});
        }
        if (activity.contains("drive") || activity.contains("driving") || activity.contains("speed") || activity.contains("helmet") || activity.contains("licen")) {
            int m = countMatches(activity, "drive", "driving", "speed", "helmet", "licen", "car", "bike", "vehicle", "highway", "traffic");
            int score = Math.min(95, Math.max(65, 72 + m * 6));
            return new Assessment("yellow",
                "Driving-related conduct is closely regulated, and violations can carry fines or license consequences depending on the specific act.",
                "The Motor Vehicles Act sets rules for speed limits, safety gear, and licensing, with dangerous driving specifically penalised.",
                new String[]{"📜 Motor Vehicles Act, 1988 — S.184"},
                "Fines, license suspension, or prosecution in cases of dangerous or reckless driving.",
                "Follow posted speed limits, wear required safety gear, and keep your license valid and on hand.",
                "Moderately supported — depends on the specific act described.",
                score,
                new Source[]{new Source("Motor Vehicles Act, 1988 — Section 184", "Legislation")});
        }
        if (activity.contains("refund") || activity.contains("warranty") || activity.contains("consumer") || activity.contains("defective")) {
            int m = countMatches(activity, "refund", "warranty", "consumer", "defective", "seller", "replacement", "product", "store", "phone");
            int score = Math.min(96, Math.max(70, 78 + m * 5));
            return new Assessment("green",
                "Seeking a refund or asserting a warranty claim is a protected consumer right, not a legal risk to you.",
                "The Consumer Protection Act gives buyers a right to remedy for defective goods or unfair trade practices.",
                new String[]{"📜 Consumer Protection Act, 2019 — S.2(47)"},
                "None to the consumer; the risk, if any, falls on a seller engaging in unfair practices.",
                "Keep purchase records and raise a written complaint before escalating to a consumer forum.",
                "Strongly supported by statute.",
                score,
                new Source[]{new Source("Consumer Protection Act, 2019 — Section 2(47)", "Legislation")});
        }
        if (activity.contains("threat") || activity.contains("intimidat") || activity.contains("assault") || activity.contains("steal") || activity.contains("theft")) {
            int m = countMatches(activity, "threat", "intimidat", "assault", "steal", "theft", "harm", "blackmail", "hurt", "extort");
            int score = Math.min(98, Math.max(75, 84 + m * 5));
            return new Assessment("red",
                "This describes conduct that is a criminal offence under Indian law.",
                "Threats, intimidation, assault, and theft are specifically defined and penalised under the criminal code.",
                new String[]{"📜 Bharatiya Nyaya Sanhita, 2023"},
                "Penalties range from fines to imprisonment depending on severity and intent.",
                "Avoid the conduct described; if you are on the receiving end, report it to local police.",
                "Strongly supported by statute.",
                score,
                new Source[]{new Source("Bharatiya Nyaya Sanhita, 2023", "Legislation")});
        }
        if (activity.contains("privacy") || activity.contains("surveillance") || activity.contains("spy") || activity.contains("track")) {
            int m = countMatches(activity, "privacy", "surveillance", "spy", "track", "hidden", "camera", "monitor", "listen");
            int score = Math.min(94, Math.max(65, 72 + m * 6));
            return new Assessment("yellow",
                "Monitoring or tracking another person without their knowledge can conflict with their constitutional right to privacy.",
                "Article 21 has been interpreted to include a right to privacy, which limits unauthorised surveillance.",
                new String[]{"🇮🇳 Constitution of India — Art. 21", "⚖️ K.S. Puttaswamy v. Union of India (2017)"},
                "Civil liability, or criminal exposure in aggravated cases.",
                "Get informed consent before monitoring or tracking someone.",
                "Moderately supported — depends on relationship and context.",
                score,
                new Source[]{new Source("K.S. Puttaswamy v. Union of India, (2017) 10 SCC 1", "Judgment")});
        }
        return new Assessment("gray",
            "There isn't enough verified information in the knowledge base to confidently assess this activity.",
            "The description may be too general, or may not match a legal provision currently indexed in the knowledge base.",
            new String[]{},
            "Unknown — insufficient matching legal sources.",
            "Try describing the activity more specifically (what, where, and with whom).",
            "Low confidence — no strong source match.",
            28,
            new Source[]{});
    }

    static class Assessment {
        String verdict, assessment, why, consequences, alternative, confidence;
        int confidenceScore;
        String[] laws;
        Source[] sources;

        Assessment(String verdict, String assessment, String why, String[] laws, String consequences,
                   String alternative, String confidence, int confidenceScore, Source[] sources) {
            this.verdict = verdict; this.assessment = assessment; this.why = why; this.laws = laws;
            this.consequences = consequences; this.alternative = alternative; this.confidence = confidence;
            this.confidenceScore = confidenceScore;
            this.sources = sources;
        }

        Assessment(String verdict, String assessment, String why, String[] laws, String consequences,
                   String alternative, String confidence, Source[] sources) {
            this(verdict, assessment, why, laws, consequences, alternative, confidence, 75, sources);
        }

        String toJson() {
            StringBuilder lawsJson = new StringBuilder("[");
            for (int i = 0; i < laws.length; i++) {
                if (i > 0) lawsJson.append(",");
                lawsJson.append("\"").append(esc(laws[i])).append("\"");
            }
            lawsJson.append("]");

            StringBuilder srcJson = new StringBuilder("[");
            for (int i = 0; i < sources.length; i++) {
                if (i > 0) srcJson.append(",");
                srcJson.append(sources[i].toJson());
            }
            srcJson.append("]");

            return "{"
                + "\"verdict\":\"" + esc(verdict) + "\","
                + "\"assessment\":\"" + esc(assessment) + "\","
                + "\"why\":\"" + esc(why) + "\","
                + "\"laws\":" + lawsJson + ","
                + "\"consequences\":\"" + esc(consequences) + "\","
                + "\"alternative\":\"" + esc(alternative) + "\","
                + "\"confidence\":\"" + esc(confidence) + "\","
                + "\"confidenceScore\":" + confidenceScore + ","
                + "\"sources\":" + srcJson
                + "}";
        }
    }

    static class Source {
        String name, type, section, date, category, excerpt;
        Source(String name, String type) { this.name = name; this.type = type; }
        Source(String name, String section, String type, String date, String category, String excerpt) {
            this.name = name; this.section = section; this.type = type;
            this.date = date; this.category = category; this.excerpt = excerpt;
        }
        String toJson() {
            return "{\"name\":\"" + esc(name) + "\",\"type\":\"" + esc(type) + "\","
                + "\"section\":\"" + esc(section) + "\",\"date\":\"" + esc(date) + "\","
                + "\"cat\":\"" + esc(category) + "\",\"excerpt\":\"" + esc(excerpt) + "\"}";
        }
    }

    // ---------------- /api/chat ----------------

    static class ChatHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (handlePreflight(ex)) return;
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 405, "{\"error\":\"POST only\"}");
                return;
            }
            String message = extractField(readBody(ex), "message").trim();
            if (message.isEmpty()) {
                sendJson(ex, 400, "{\"error\":\"Enter a question to start chatting.\"}");
                return;
            }
            if (message.length() > 5000) {
                sendJson(ex, 413, "{\"error\":\"Please keep your question under 5,000 characters.\"}");
                return;
            }

            String apiKey = System.getenv("OPENAI_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                sendJson(ex, 503, "{\"error\":\"AI is not configured. Set OPENAI_API_KEY on the server and restart Nagarik AI.\"}");
                return;
            }

            String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");
            String baseUrl = System.getenv().getOrDefault("OPENAI_BASE_URL", "https://api.openai.com/v1");
            while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            String prompt = "You are Nagarik AI, a helpful assistant for the public. Answer questions clearly and directly, "
                + "with a focus on Indian law and civic rights. You may answer general questions too. For legal questions, "
                + "explain uncertainty, ask for missing jurisdiction or facts when they matter, and never invent statutes, "
                + "sections, cases, or citations. Give practical next steps where useful. This is legal information, not "
                + "a substitute for advice from a qualified advocate; recommend urgent professional help for high-stakes matters.";
            String requestBody = "{\"model\":\"" + esc(model) + "\",\"messages\":["
                + "{\"role\":\"system\",\"content\":\"" + esc(prompt) + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + esc(message) + "\"}],\"temperature\":0.2}";

            HttpRequest request;
            try {
                request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(45))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            } catch (IllegalArgumentException e) {
                sendJson(ex, 500, "{\"error\":\"The configured AI endpoint is invalid. Check OPENAI_BASE_URL.\"}");
                return;
            }

            try {
                HttpResponse<String> response = AI_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    sendJson(ex, 502, "{\"error\":\"The AI provider could not answer right now. Check the server key, model, and provider status.\"}");
                    return;
                }
                String answer = extractField(response.body(), "content").trim();
                if (answer.isEmpty()) {
                    sendJson(ex, 502, "{\"error\":\"The AI provider returned an empty answer. Please try again.\"}");
                    return;
                }
                sendJson(ex, 200, "{\"blocks\":[{\"heading\":\"AI Answer\",\"text\":\"" + esc(answer) + "\"}]}");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sendJson(ex, 502, "{\"error\":\"The AI request was interrupted. Please try again.\"}");
            } catch (IOException e) {
                sendJson(ex, 502, "{\"error\":\"Could not reach the AI provider. Check the server connection and try again.\"}");
            }
        }
    }

    // ---------------- /api/sources ----------------

    static final Source[] SOURCE_DB = new Source[]{
        new Source("Information Technology Act, 2000", "Section 66E — Privacy Violation", "Legislation", "2000 (amended 2008)", "Cyber Law",
            "Deals with punishment for capturing or publishing images of a person's private area without consent."),
        new Source("Constitution of India", "Article 21 — Right to Life & Personal Liberty", "Constitutional Provision", "1950", "Constitution",
            "Interpreted by courts to include the right to privacy as part of personal liberty."),
        new Source("K.S. Puttaswamy v. Union of India", "(2017) 10 SCC 1", "Court Judgment", "2017", "Privacy",
            "Nine-judge bench recognised the right to privacy as a fundamental right under the Constitution."),
        new Source("Motor Vehicles Act, 1988", "Section 184 — Dangerous Driving", "Legislation", "1988 (amended 2019)", "Traffic",
            "Prescribes penalties for driving in a manner dangerous to the public."),
        new Source("Consumer Protection Act, 2019", "Section 2(47) — Unfair Trade Practice", "Legislation", "2019", "Consumer Law",
            "Defines practices that mislead consumers and the remedies available to them."),
        new Source("Bharatiya Nyaya Sanhita, 2023", "Provisions on criminal intimidation", "Legislation", "2023", "Criminal Law",
            "Sets out what constitutes criminal intimidation and applicable penalties.")
    };

    static class SourcesHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (handlePreflight(ex)) return;
            Map<String, String> params = queryParams(ex);
            String category = params.getOrDefault("category", "all");
            String q = params.getOrDefault("q", "").toLowerCase();

            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (Source s : SOURCE_DB) {
                if (!"all".equalsIgnoreCase(category) && !s.category.equalsIgnoreCase(category)) continue;
                if (!q.isEmpty() && !(s.name + s.excerpt).toLowerCase().contains(q)) continue;
                if (!first) json.append(",");
                json.append(s.toJson());
                first = false;
            }
            json.append("]");
            sendJson(ex, 200, json.toString());
        }
    }
}