package com.flyo.rescue.dto;

import java.time.LocalDateTime;

public class AirportWeatherTelemetry {

    private String airportCode;
    private String airportName;
    private double latitude;
    private double longitude;
    private double temperatureC;
    private int relativeHumidity;
    private double visibilityMeters;
    private int weatherCode;
    private String weatherCondition;
    private double windSpeedKmh;
    private boolean adverseWeather;
    private String source;
    private LocalDateTime timestamp;

    public AirportWeatherTelemetry() {
        this.timestamp = LocalDateTime.now();
    }

    public AirportWeatherTelemetry(String airportCode, String airportName, double latitude, double longitude,
                                  double temperatureC, int relativeHumidity, double visibilityMeters,
                                  int weatherCode, String weatherCondition, double windSpeedKmh,
                                  boolean adverseWeather, String source) {
        this.airportCode = airportCode;
        this.airportName = airportName;
        this.latitude = latitude;
        this.longitude = longitude;
        this.temperatureC = temperatureC;
        this.relativeHumidity = relativeHumidity;
        this.visibilityMeters = visibilityMeters;
        this.weatherCode = weatherCode;
        this.weatherCondition = weatherCondition;
        this.windSpeedKmh = windSpeedKmh;
        this.adverseWeather = adverseWeather;
        this.source = source;
        this.timestamp = LocalDateTime.now();
    }

    public String getAirportCode() {
        return airportCode;
    }

    public void setAirportCode(String airportCode) {
        this.airportCode = airportCode;
    }

    public String getAirportName() {
        return airportName;
    }

    public void setAirportName(String airportName) {
        this.airportName = airportName;
    }

    public double getLatitude() {
        return latitude;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public double getTemperatureC() {
        return temperatureC;
    }

    public void setTemperatureC(double temperatureC) {
        this.temperatureC = temperatureC;
    }

    public int getRelativeHumidity() {
        return relativeHumidity;
    }

    public void setRelativeHumidity(int relativeHumidity) {
        this.relativeHumidity = relativeHumidity;
    }

    public double getVisibilityMeters() {
        return visibilityMeters;
    }

    public void setVisibilityMeters(double visibilityMeters) {
        this.visibilityMeters = visibilityMeters;
    }

    public int getWeatherCode() {
        return weatherCode;
    }

    public void setWeatherCode(int weatherCode) {
        this.weatherCode = weatherCode;
    }

    public String getWeatherCondition() {
        return weatherCondition;
    }

    public void setWeatherCondition(String weatherCondition) {
        this.weatherCondition = weatherCondition;
    }

    public double getWindSpeedKmh() {
        return windSpeedKmh;
    }

    public void setWindSpeedKmh(double windSpeedKmh) {
        this.windSpeedKmh = windSpeedKmh;
    }

    public boolean isAdverseWeather() {
        return adverseWeather;
    }

    public void setAdverseWeather(boolean adverseWeather) {
        this.adverseWeather = adverseWeather;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
