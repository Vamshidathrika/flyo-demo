package com.flyo.rescue.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flyo.rescue.dto.AirportWeatherTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OpenMeteoWeatherService {

    private static final Logger log = LoggerFactory.getLogger(OpenMeteoWeatherService.class);
    private static final long CACHE_TTL_SECONDS = 300; // 5-minute cache

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    // Cache entry wrapper
    private static class CachedWeather {
        final AirportWeatherTelemetry telemetry;
        final long cachedAtEpochMs;

        CachedWeather(AirportWeatherTelemetry telemetry) {
            this.telemetry = telemetry;
            this.cachedAtEpochMs = System.currentTimeMillis();
        }

        boolean isExpired() {
            return (System.currentTimeMillis() - cachedAtEpochMs) > (CACHE_TTL_SECONDS * 1000);
        }
    }

    private final Map<String, CachedWeather> weatherCache = new ConcurrentHashMap<>();

    // Static airport registry with geographic coordinates for free Open-Meteo queries
    private static final Map<String, AirportCoord> AIRPORT_COORDINATES = Map.of(
            "DEL", new AirportCoord("DEL", "Indira Gandhi International, New Delhi", 28.5562, 77.1000),
            "BOM", new AirportCoord("BOM", "Chhatrapati Shivaji Maharaj International, Mumbai", 19.0896, 72.8656),
            "BLR", new AirportCoord("BLR", "Kempegowda International, Bengaluru", 13.1986, 77.7066),
            "HYD", new AirportCoord("HYD", "Rajiv Gandhi International, Hyderabad", 17.2403, 78.4294),
            "MAA", new AirportCoord("MAA", "Chennai International, Chennai", 12.9941, 80.1709),
            "CCU", new AirportCoord("CCU", "Netaji Subhash Chandra Bose International, Kolkata", 22.6547, 88.4467),
            "YYZ", new AirportCoord("YYZ", "Toronto Pearson International, Toronto", 43.6777, -79.6248),
            "LHR", new AirportCoord("LHR", "Heathrow Airport, London", 51.4700, -0.4543),
            "DXB", new AirportCoord("DXB", "Dubai International Airport, Dubai", 25.2532, 55.3657),
            "SIN", new AirportCoord("SIN", "Singapore Changi Airport, Singapore", 1.3644, 103.9915)
    );

    public static class AirportCoord {
        public final String code;
        public final String name;
        public final double latitude;
        public final double longitude;

        public AirportCoord(String code, String name, double latitude, double longitude) {
            this.code = code;
            this.name = name;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    public OpenMeteoWeatherService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public AirportWeatherTelemetry getWeatherForAirport(String rawAirportCode) {
        if (rawAirportCode == null || rawAirportCode.isBlank()) {
            return getFallbackWeather("DEL", "Indira Gandhi International, New Delhi", 28.5562, 77.1000);
        }

        String airportCode = rawAirportCode.trim().toUpperCase().replaceAll("[^A-Z0-9]", "");
        if (airportCode.isBlank() || airportCode.length() > 4) {
            airportCode = "DEL";
        }
        CachedWeather cached = weatherCache.get(airportCode);
        if (cached != null && !cached.isExpired()) {
            log.debug("[Open-Meteo] Serving cached weather for airport {}", airportCode);
            return cached.telemetry;
        }

        AirportCoord coord = AIRPORT_COORDINATES.getOrDefault(airportCode,
                new AirportCoord(airportCode, airportCode + " Airport", 28.5562, 77.1000));

        try {
            String uriString = String.format(
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,visibility&timezone=auto",
                    coord.latitude, coord.longitude
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(uriString))
                    .timeout(Duration.ofSeconds(4))
                    .header("Accept", "application/json")
                    .header("User-Agent", "FlyoRescueEngine/1.0 (Aviation Operations)")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                AirportWeatherTelemetry telemetry = parseOpenMeteoResponse(response.body(), coord);
                weatherCache.put(airportCode, new CachedWeather(telemetry));
                log.info("[Open-Meteo] Fetched live weather for {}: {}°C, {}m visibility, Condition: {}",
                        airportCode, telemetry.getTemperatureC(), telemetry.getVisibilityMeters(), telemetry.getWeatherCondition());
                return telemetry;
            } else {
                log.warn("[Open-Meteo] API returned HTTP {} for airport {}. Using resilient baseline.", response.statusCode(), airportCode);
            }
        } catch (Exception e) {
            log.warn("[Open-Meteo] Live query failed for airport {} ({}). Using resilient fallback.", airportCode, e.getMessage());
        }

        // Return resilient baseline
        AirportWeatherTelemetry fallback = getFallbackWeather(coord.code, coord.name, coord.latitude, coord.longitude);
        weatherCache.put(airportCode, new CachedWeather(fallback));
        return fallback;
    }

    private AirportWeatherTelemetry parseOpenMeteoResponse(String json, AirportCoord coord) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode current = root.path("current");

            double tempC = current.path("temperature_2m").asDouble(25.0);
            int humidity = current.path("relative_humidity_2m").asInt(60);
            int weatherCode = current.path("weather_code").asInt(0);
            double windSpeed = current.path("wind_speed_10m").asDouble(10.0);
            double visibilityMeters = current.path("visibility").asDouble(10000.0);

            String condition = mapWmoWeatherCode(weatherCode);
            // Adverse weather condition: Fog (45, 48), thunderstorm (95, 96, 99), or low visibility (< 1000m CAT II/III)
            boolean isAdverse = (weatherCode == 45 || weatherCode == 48 || weatherCode >= 95 || visibilityMeters < 1200.0);

            return new AirportWeatherTelemetry(
                    coord.code,
                    coord.name,
                    coord.latitude,
                    coord.longitude,
                    tempC,
                    humidity,
                    visibilityMeters,
                    weatherCode,
                    condition,
                    windSpeed,
                    isAdverse,
                    "Open-Meteo Live API"
            );
        } catch (Exception e) {
            log.error("[Open-Meteo] Parsing failed: {}", e.getMessage());
            return getFallbackWeather(coord.code, coord.name, coord.latitude, coord.longitude);
        }
    }

    private String mapWmoWeatherCode(int code) {
        return switch (code) {
            case 0 -> "Clear Sky";
            case 1, 2, 3 -> "Partly Cloudy";
            case 45, 48 -> "Dense Fog (CAT III ILS Active)";
            case 51, 53, 55 -> "Light Drizzle";
            case 61, 63, 65 -> "Continuous Rain";
            case 71, 73, 75 -> "Snow Fall";
            case 80, 81, 82 -> "Rain Showers";
            case 95, 96, 99 -> "Severe Thunderstorm";
            default -> "Overcast / Haze";
        };
    }

    private AirportWeatherTelemetry getFallbackWeather(String code, String name, double lat, double lon) {
        return new AirportWeatherTelemetry(
                code,
                name,
                lat,
                lon,
                0.0,
                0,
                0.0,
                -1,
                "WEATHER_TELEMETRY_UNAVAILABLE",
                0.0,
                false,
                "UNAVAILABLE"
        );
    }
}
