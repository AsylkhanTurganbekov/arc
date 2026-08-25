package kz.belesai.arc.jira;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JiraClient {
    private final RestClient client;
    private final long cacheSeconds;
    private final Map<String, CachedValue> cache = new ConcurrentHashMap<>();

    public JiraClient(
            @Value("${arc.jira.base-url}") String baseUrl,
            @Value("${arc.jira.username}") String username,
            @Value("${arc.jira.api-token}") String apiToken,
            @Value("${arc.jira.cache-seconds:180}") long cacheSeconds
    ) {
        if (username.isBlank() || apiToken.isBlank()) {
            throw new IllegalStateException("JIRA_USERNAME and JIRA_API_TOKEN are required");
        }
        this.cacheSeconds = cacheSeconds;
        this.client = RestClient.builder()
                .baseUrl(baseUrl.replaceAll("/+$", ""))
                .defaultHeaders(headers -> headers.setBasicAuth(username, apiToken, StandardCharsets.UTF_8))
                .build();
    }

    public JsonNode projects() {
        return cached("projects", () -> get("/rest/api/2/project"));
    }

    public JsonNode fields() {
        return cached("fields", () -> get("/rest/api/2/field"));
    }

    public JsonNode users() {
        return cached("users", this::searchAllUsers);
    }

    public String sprintFieldId() {
        JsonNode fields = fields();
        for (JsonNode field : fields) {
            String name = field.path("name").asText("").toLowerCase(Locale.ROOT);
            if (name.equals("спринт") || name.equals("sprint")) {
                return field.path("id").asText("customfield_10105");
            }
        }
        return "customfield_10105";
    }

    public List<JsonNode> projectIssues(String projectKey) {
        String safeKey = projectKey.replaceAll("[^A-Za-z0-9_-]", "").toUpperCase(Locale.ROOT);
        String sprintField = sprintFieldId();
        String cacheKey = "issues:" + safeKey + ":" + sprintField;
        JsonNode cachedPage = cached(cacheKey, () -> searchAll(
                "project = \"" + safeKey + "\" ORDER BY updated DESC",
                List.of("summary", "status", "assignee", "priority", "issuetype", "created", "updated",
                        "resolutiondate", "components", "labels", sprintField)
        ));
        List<JsonNode> issues = new ArrayList<>();
        cachedPage.forEach(issues::add);
        return issues;
    }

    public List<JsonNode> projectIssuesWithChangelog(String projectKey) {
        String safeKey = projectKey.replaceAll("[^A-Za-z0-9_-]", "").toUpperCase(Locale.ROOT);
        String sprintField = sprintFieldId();
        String cacheKey = "history-issues:" + safeKey + ":" + sprintField;
        JsonNode cachedPage = cached(cacheKey, () -> searchAll(
                "project = \"" + safeKey + "\" ORDER BY updated DESC",
                List.of("summary", "status", "assignee", "priority", "issuetype", "created", "updated",
                        "resolutiondate", "components", "labels", sprintField),
                "changelog"
        ), 300);
        List<JsonNode> issues = new ArrayList<>();
        cachedPage.forEach(issues::add);
        return issues;
    }

    public JsonNode issueWithChangelog(String issueKey) {
        String safeKey = issueKey.replaceAll("[^A-Za-z0-9_-]", "").toUpperCase(Locale.ROOT);
        return cached("changelog:" + safeKey,
                () -> get("/rest/api/2/issue/" + safeKey + "?expand=changelog"), 90);
    }

    public void evictProject(String projectKey) {
        String safeKey = projectKey.toUpperCase(Locale.ROOT);
        String issuesPrefix = "issues:" + safeKey + ":";
        String historyPrefix = "history-issues:" + safeKey + ":";
        cache.keySet().removeIf(key -> key.startsWith(issuesPrefix) || key.startsWith(historyPrefix));
    }

    private JsonNode searchAll(String jql, List<String> fields) {
        return searchAll(jql, fields, null);
    }

    private JsonNode searchAll(String jql, List<String> fields, String expand) {
        com.fasterxml.jackson.databind.node.ArrayNode result = new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();
        int startAt = 0;
        int total;
        do {
            JsonNode page = searchPage(jql, startAt, fields, expand);
            JsonNode pageIssues = page.path("issues");
            pageIssues.forEach(result::add);
            total = page.path("total").asInt(result.size());
            startAt += pageIssues.size();
            if (pageIssues.isEmpty()) {
                break;
            }
        } while (startAt < total && startAt < 5000);
        return result;
    }

    private JsonNode searchPage(String jql, int startAt, List<String> fields, String expand) {
        try {
            JsonNode body = client.get()
                    .uri(builder -> {
                        builder.path("/rest/api/2/search")
                                .queryParam("jql", jql)
                                .queryParam("startAt", startAt)
                                .queryParam("maxResults", 100)
                                .queryParam("fields", String.join(",", fields));
                        if (expand != null && !expand.isBlank()) builder.queryParam("expand", expand);
                        return builder.build();
                    })
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Jira returned an empty search response");
            }
            return body;
        } catch (RestClientResponseException error) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Jira search failed with status " + error.getStatusCode().value()
            );
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Jira search is unavailable");
        }
    }

    private JsonNode searchAllUsers() {
        com.fasterxml.jackson.databind.node.ArrayNode result = new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();
        int startAt = 0;
        while (startAt < 5000) {
            JsonNode page = searchUsersPage(startAt);
            if (!page.isArray() || page.isEmpty()) break;
            page.forEach(result::add);
            startAt += page.size();
        }
        return result;
    }

    private JsonNode searchUsersPage(int startAt) {
        try {
            JsonNode body = client.get()
                    .uri(builder -> builder.path("/rest/api/2/user/search")
                            .queryParam("username", ".")
                            .queryParam("startAt", startAt)
                            .queryParam("maxResults", 100)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Jira returned an empty users response");
            }
            return body;
        } catch (RestClientResponseException error) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Jira users search failed with status " + error.getStatusCode().value()
            );
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Jira users search is unavailable");
        }
    }

    private JsonNode get(String path) {
        try {
            JsonNode body = client.get().uri(path).retrieve().body(JsonNode.class);
            if (body == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Jira returned an empty response");
            }
            return body;
        } catch (RestClientResponseException error) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Jira request failed with status " + error.getStatusCode().value()
            );
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Jira is unavailable");
        }
    }

    private JsonNode cached(String key, NodeSupplier supplier) {
        return cached(key, supplier, cacheSeconds);
    }

    private JsonNode cached(String key, NodeSupplier supplier, long ttlSeconds) {
        CachedValue value = cache.get(key);
        if (value != null && Duration.between(value.createdAt(), Instant.now()).toSeconds() < ttlSeconds) {
            return value.value();
        }
        JsonNode fresh = supplier.get();
        cache.put(key, new CachedValue(fresh, Instant.now()));
        return fresh;
    }

    @FunctionalInterface
    private interface NodeSupplier {
        JsonNode get();
    }

    private record CachedValue(JsonNode value, Instant createdAt) {}
}
