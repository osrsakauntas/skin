package com.kodari.skinoutfitswapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MineSkinClient {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private static final Pattern TEXTURE_VALUE = Pattern.compile("\\\"value\\\"\\s*:\\s*\\\"([A-Za-z0-9+/=_-]+)\\\"");
    private static final Pattern TEXTURE_SIGNATURE = Pattern.compile("\\\"signature\\\"\\s*:\\s*\\\"([A-Za-z0-9+/=_-]+)\\\"");
    private static final Pattern TEXTURE_URL = Pattern.compile("\\\"url\\\"\\s*:\\s*\\\"(https?://[^\\\"]+)\\\"");
    private static final Pattern MODEL = Pattern.compile("\\\"model\\\"\\s*:\\s*\\\"(slim|classic)\\\"", Pattern.CASE_INSENSITIVE);

    private MineSkinClient() {
    }

    static GeneratedSkin upload(byte[] image, String endpoint, String apiKey) throws Exception {
        String boundary = "KodariSkin" + Long.toHexString(System.nanoTime());
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(image);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("User-Agent", "SkinOutfitSwapper/1.0")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if (apiKey != null && !apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey.trim());
        }

        HttpResponse<String> response = HTTP.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("MineSkin atsakė HTTP " + response.statusCode() + ": " + response.body());
        }

        Matcher valueMatcher = TEXTURE_VALUE.matcher(response.body());
        if (!valueMatcher.find()) {
            throw new IOException("MineSkin atsakyme nerasta tekstūros reikšmė.");
        }

        Matcher signatureMatcher = TEXTURE_SIGNATURE.matcher(response.body());
        if (!signatureMatcher.find()) {
            throw new IOException("MineSkin atsakyme nerastas tekstūros parašas.");
        }

        String encodedValue = valueMatcher.group(1);
        String signature = signatureMatcher.group(1);
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedValue);
        } catch (IllegalArgumentException exception) {
            decoded = Base64.getUrlDecoder().decode(encodedValue);
        }
        String textureData = new String(decoded, StandardCharsets.UTF_8);
        Matcher urlMatcher = TEXTURE_URL.matcher(textureData);
        if (!urlMatcher.find()) {
            throw new IOException("MineSkin tekstūroje nerasta odos nuoroda.");
        }

        Matcher modelMatcher = MODEL.matcher(textureData);
        String model = modelMatcher.find() ? modelMatcher.group(1).toUpperCase() : "CLASSIC";
        return new GeneratedSkin(urlMatcher.group(1), model, encodedValue, signature);
    }

    record GeneratedSkin(String skinUrl, String model, String textureValue, String signature) {
    }
}