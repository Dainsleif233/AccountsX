package top.syshub.accountsx.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal password-only Yggdrasil server for client gametests: root metadata,
 * authenticate/refresh/validate/invalidate and the session profile endpoint.
 * No OAuth discovery, so the login UI stays in password mode.
 *
 * <p>It validates the protocol, not just the path: authenticate must carry the right
 * credentials and a clientToken, and refresh/validate/invalidate must echo that same
 * clientToken with a token this server actually issued (drasl enforces this; a client
 * that drops or rotates the clientToken wrongly gets 403). A wrong request therefore
 * fails the flow instead of silently returning success. All values use the fake- prefix.
 */
final class FakeYggdrasilServer implements AutoCloseable {
    static final String PLAYER = "GtExternal";
    static final String UUID32 = "11111111222233334444555566667777";
    static final String USERNAME = "fake-user";
    static final String PASSWORD = "fake-pass";

    private final HttpServer server;
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private final Set<String> issuedTokens = ConcurrentHashMap.newKeySet();
    private final AtomicInteger validInvalidates = new AtomicInteger();
    private volatile String clientToken;
    final String base;
    final String name;

    FakeYggdrasilServer() throws IOException {
        this(0);
    }

    /**
     * @param port local port to bind; 0 picks an ephemeral one. The authlib-injector
     *             compatibility run needs a fixed port because the agent's API root
     *             is baked into its run configuration before the test JVM starts.
     */
    FakeYggdrasilServer(int port) throws IOException {
        this(port, "GtYggdrasil");
    }

    /** @param name display name published in root metadata; distinct names let a test tell two servers apart. */
    FakeYggdrasilServer(int port, String name) throws IOException {
        this.name = name;
        String pem = rsaPublicKeyPem();
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.base = "http://127.0.0.1:" + server.getAddress().getPort();

        server.createContext("/", exchange -> {
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            hits.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject req = raw.isEmpty() ? new JsonObject() : JsonParser.parseString(raw).getAsJsonObject();
            JsonObject body = null;
            int code = 200;
            switch (key) {
                case "GET /" -> body = rootMeta(pem, name);
                case "POST /authserver/authenticate" -> {
                    if (!USERNAME.equals(str(req, "username")) || !PASSWORD.equals(str(req, "password"))) {
                        code = 403;
                        body = error("ForbiddenOperationException", "Invalid credentials");
                    } else {
                        String token = str(req, "clientToken");
                        if (token == null || token.isEmpty()) {
                            code = 400;
                            body = error("IllegalArgumentException", "Missing clientToken");
                        } else {
                            clientToken = token;
                            body = authResponse("fake-access", token);
                        }
                    }
                }
                case "POST /authserver/refresh" -> {
                    if (!tokenValid(req)) {
                        code = 403;
                        body = error("ForbiddenOperationException", "Invalid token");
                    } else {
                        body = authResponse("fake-access-refreshed", clientToken);
                    }
                }
                case "POST /authserver/validate" -> {
                    if (!tokenValid(req)) {
                        code = 403;
                        body = error("ForbiddenOperationException", "Invalid token");
                    } else {
                        code = 204;
                    }
                }
                case "POST /authserver/invalidate" -> {
                    if (!tokenValid(req)) {
                        code = 403;
                        body = error("ForbiddenOperationException", "Invalid token");
                    } else {
                        validInvalidates.incrementAndGet();
                        code = 204;
                    }
                }
                case "GET /sessionserver/session/minecraft/profile/" + UUID32 -> {
                    body = new JsonObject();
                    body.addProperty("id", UUID32);
                    body.addProperty("name", PLAYER);
                    body.add("properties", new JsonArray());
                }
                default -> code = 404;
            }
            byte[] bytes = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
            if (body != null) {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
            }
            exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }

    /** refresh/validate/invalidate must echo the clientToken from authenticate and a token we issued. */
    private boolean tokenValid(JsonObject req) {
        String token = str(req, "clientToken");
        String access = str(req, "accessToken");
        return clientToken != null && clientToken.equals(token) && access != null && issuedTokens.contains(access);
    }

    private static String str(JsonObject obj, String field) {
        return obj.has(field) && !obj.get(field).isJsonNull() ? obj.get(field).getAsString() : null;
    }

    private static JsonObject error(String type, String message) {
        JsonObject json = new JsonObject();
        json.addProperty("error", type);
        json.addProperty("errorMessage", message);
        return json;
    }

    int hits(String methodAndPath) {
        AtomicInteger count = hits.get(methodAndPath);
        return count == null ? 0 : count.get();
    }

    /** Invalidates that passed protocol validation (a 403 still counts as a hit, not as a valid invalidate). */
    int validInvalidates() {
        return validInvalidates.get();
    }

    private JsonObject rootMeta(String pem, String name) {
        JsonObject meta = new JsonObject();
        meta.addProperty("serverName", name);
        JsonObject root = new JsonObject();
        root.addProperty("signaturePublickey", pem);
        JsonArray domains = new JsonArray();
        domains.add("127.0.0.1");
        root.add("skinDomains", domains);
        root.add("meta", meta);
        return root;
    }

    private JsonObject authResponse(String accessToken, String token) {
        issuedTokens.add(accessToken);
        JsonObject profile = new JsonObject();
        profile.addProperty("name", PLAYER);
        profile.addProperty("id", UUID32);
        JsonArray available = new JsonArray();
        available.add(profile);
        JsonObject json = new JsonObject();
        json.addProperty("accessToken", accessToken);
        json.addProperty("clientToken", token);
        json.add("availableProfiles", available);
        // Server already bound the single profile: the UI must skip role selection.
        json.add("selectedProfile", profile);
        return json;
    }

    private static String rsaPublicKeyPem() throws IOException {
        try {
            var pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
            return "-----BEGIN PUBLIC KEY-----"
                    + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())
                    + "-----END PUBLIC KEY-----";
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
