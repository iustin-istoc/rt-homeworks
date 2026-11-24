package ssatr.tracker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Clientul API-ului REST CTP Cluj live: liniile și traseul/stațiile unei linii. */
public final class CtpApi {

    public record Direction(String tripId, int directionId, String headsign) { }

    public record Line(int routeId, String line, String name, String type, List<Direction> directions) {
        @Override public String toString() { return line + " (" + type + ", " + name + ")"; }
    }

    public record Stop(int id, String name, double lat, double lon) { }

    /** Un sens al liniei: traseul (puncte lat/lon, în ordine) și stațiile, în ordinea parcurgerii. */
    public record Route(String tripId, String headsign, List<double[]> shape, List<Stop> stops) { }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl;
    private final String apiKey;

    public CtpApi(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("X-API-Key", apiKey)
                .timeout(Duration.ofSeconds(15))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("API " + path + ": HTTP " + resp.statusCode() + " " + resp.body());
        }
        return JSON.readTree(resp.body());
    }

    public List<Line> lines() throws IOException, InterruptedException {
        List<Line> out = new ArrayList<>();
        for (JsonNode n : get("/api/lines").path("lines")) {
            List<Direction> dirs = new ArrayList<>();
            for (JsonNode d : n.path("directions")) {
                dirs.add(new Direction(d.path("trip_id").asText(), d.path("direction_id").asInt(),
                        d.path("headsign").asText("")));
            }
            out.add(new Line(n.path("route_id").asInt(), n.path("line").asText("?"),
                    n.path("name").asText(""), n.path("type").asText(""), dirs));
        }
        return out;
    }

    public List<Route> routes(int routeId) throws IOException, InterruptedException {
        List<Route> out = new ArrayList<>();
        for (JsonNode d : get("/api/line?route_id=" + routeId).path("directions")) {
            List<double[]> shape = new ArrayList<>();
            for (JsonNode p : d.path("shape")) {
                if (p.size() >= 2) shape.add(new double[]{p.get(0).asDouble(), p.get(1).asDouble()});
            }
            List<Stop> stops = new ArrayList<>();
            for (JsonNode s : d.path("stops")) {
                stops.add(new Stop(s.path("id").asInt(), s.path("name").asText(""),
                        s.path("lat").asDouble(), s.path("lon").asDouble()));
            }
            out.add(new Route(d.path("trip_id").asText(), d.path("headsign").asText(""), shape, stops));
        }
        return out;
    }
}
