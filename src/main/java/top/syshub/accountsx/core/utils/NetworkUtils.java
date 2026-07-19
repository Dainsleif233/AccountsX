package top.syshub.accountsx.core.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import top.syshub.accountsx.core.accounts.AccountUUID;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class NetworkUtils {
    public static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(UUID.class, new AccountUUID.UUIDTypeAdapter())
            .setPrettyPrinting()
            .create();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public static HttpRequest buildGet(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .GET()
                .build();
    }

    public static HttpRequest buildGet(String url, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            builder.header(e.getKey(), e.getValue());
        }
        return builder.build();
    }

    public static Map<String, List<String>> headRequest(String url) throws IOException {
        URI uri = URI.create(url);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            HttpResponse<Void> response = CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            return response.headers().map();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.", e);
        }
    }

    public static List<String> getHeaderIgnoreCase(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    public static String resolveLocation(String base, String loc) throws IOException {
        try {
            URI baseUri = URI.create(base);
            URI locUri = URI.create(loc);
            URI next = locUri.isAbsolute() ? locUri : baseUri.resolve(locUri);
            return next.toString();
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid redirect location", e);
        }
    }

    public static JsonObject postRequest(HttpRequest request) throws IOException {
        return postRequest(request, false);
    }

    public static JsonObject postRequest(HttpRequest request, boolean ignoreHttpStatus) throws IOException {
        try {
            HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            try (Reader reader = NetworkUtils.readResponse(response, ignoreHttpStatus)) {
                return NetworkUtils.GSON.fromJson(reader, JsonObject.class);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.", e);
        }
    }

    public static JsonObject postRequest(String url, JsonElement json) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(NetworkUtils.GSON.toJson(json)))
                .build();
        return postRequest(request);
    }

    public static JsonObject postRequest(String url, Map<String, String> formData, boolean ignoreHttpStatus) throws IOException {
        String body = encodeForm(formData);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return postRequest(request, ignoreHttpStatus);
    }

    public static JsonObject postRequest(String url, Map<String, String> formData) throws IOException {
        return postRequest(url, formData, false);
    }

    private static String encodeForm(Map<String, String> formData) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : formData.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    public static Reader readResponse(HttpResponse<byte[]> response, boolean ignoreHttpStatus) throws IOException {
        if (!ignoreHttpStatus) {
            int statusCode = response.statusCode();
            if (statusCode / 100 != 2) {
                throw new IOException("HTTP " + statusCode);
            }
        }

        Charset charset = StandardCharsets.UTF_8;
        Optional<String> contentType = response.headers().firstValue("Content-Type");
        if (contentType.isPresent()) {
            String ct = contentType.get().toLowerCase(Locale.ROOT);
            int idx = ct.indexOf("charset=");
            if (idx != -1) {
                String cs = ct.substring(idx + 8).trim();
                int semi = cs.indexOf(';');
                if (semi != -1) cs = cs.substring(0, semi);
                cs = cs.replace("\"", "").trim();
                try {
                    charset = Charset.forName(cs);
                } catch (Exception ignored) {
                }
            }
        }

        return new InputStreamReader(new java.io.ByteArrayInputStream(response.body()), charset);
    }
}
