package net.burningtnt.accountsx.core.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.burningtnt.accountsx.core.accounts.AccountUUID;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultRedirectStrategy;
import org.apache.http.impl.client.HttpClientBuilder;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

public class NetworkUtils {
    public static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(UUID.class, new AccountUUID.UUIDTypeAdapter())
            .setPrettyPrinting()
            .create();

    private static final HttpClientBuilder BUILDER = HttpClientBuilder.create().setRedirectStrategy(new DefaultRedirectStrategy());

    public static JsonObject postRequest(HttpUriRequest request) throws IOException {
        return postRequest(request, false);
    }

    public static JsonObject postRequest(HttpUriRequest request, boolean ignoreHttpStatus) throws IOException {
        try (CloseableHttpClient httpClient = BUILDER.build()) {
            try (Reader reader = NetworkUtils.readResponse(httpClient.execute(request), ignoreHttpStatus)) {
                return NetworkUtils.GSON.fromJson(reader, JsonObject.class);
            }
        }
    }

    public static JsonObject postRequest(String url, JsonElement json) throws IOException {
        return postRequest(RequestBuilder.post(url)
                .addHeader("Content-Type", "application/json")
                .setEntity(new StringEntity(NetworkUtils.GSON.toJson(json)))
                .build());
    }

    public static JsonObject postRequest(String url, Map<String, String> formData, boolean ignoreHttpStatus) throws IOException {
        RequestBuilder requestBuilder = RequestBuilder.post(url)
                .addHeader("Content-Type", "application/x-www-form-urlencoded");
        for (java.util.Map.Entry<String, String> entry : formData.entrySet())
            requestBuilder.addParameter(entry.getKey(), entry.getValue());
        return postRequest(requestBuilder.build(), ignoreHttpStatus);
    }

    public static JsonObject postRequest(String url, Map<String, String> formData) throws IOException {
        return postRequest(url, formData, false);
    }

    public static Reader readResponse(HttpResponse response, boolean ignoreHttpStatus) throws IOException {
        if (!ignoreHttpStatus) {
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode / 100 != 2) {
                throw new IOException("HTTP " + statusCode + ": " + response.getStatusLine().getReasonPhrase());
            }
        }

        Header encoding = response.getEntity().getContentEncoding();
        if (encoding == null) {
            return new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8);
        }

        return new InputStreamReader(response.getEntity().getContent(), encoding.getValue());
    }
}
