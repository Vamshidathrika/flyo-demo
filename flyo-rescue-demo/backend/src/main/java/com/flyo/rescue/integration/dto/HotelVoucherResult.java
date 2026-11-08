package com.flyo.rescue.integration.dto;

public class HotelVoucherResult {
    private boolean reserved;
    private String voucherCode;
    private String hotelName;
    private String hotelAddress;
    private String shuttleInstructions;
    private int nightRate;
    private String errorMessage;

    public HotelVoucherResult() {}

    public static HotelVoucherResult success(String voucherCode, String hotelName, String hotelAddress, String shuttleInstructions, int nightRate) {
        HotelVoucherResult res = new HotelVoucherResult();
        res.reserved = true;
        res.voucherCode = voucherCode;
        res.hotelName = hotelName;
        res.hotelAddress = hotelAddress;
        res.shuttleInstructions = shuttleInstructions;
        res.nightRate = nightRate;
        return res;
    }

    public static HotelVoucherResult failure(String errorMessage) {
        HotelVoucherResult res = new HotelVoucherResult();
        res.reserved = false;
        res.errorMessage = errorMessage;
        return res;
    }

    public static HotelVoucherResult failed(String errorCode, String errorMessage) {
        HotelVoucherResult res = new HotelVoucherResult();
        res.reserved = false;
        res.errorMessage = errorCode + ": " + errorMessage;
        return res;
    }

    public boolean isReserved() { return reserved; }
    public void setReserved(boolean reserved) { this.reserved = reserved; }
    public String getVoucherCode() { return voucherCode; }
    public void setVoucherCode(String voucherCode) { this.voucherCode = voucherCode; }
    public String getHotelName() { return hotelName; }
    public void setHotelName(String hotelName) { this.hotelName = hotelName; }
    public String getHotelAddress() { return hotelAddress; }
    public void setHotelAddress(String hotelAddress) { this.hotelAddress = hotelAddress; }
    public String getShuttleInstructions() { return shuttleInstructions; }
    public void setShuttleInstructions(String shuttleInstructions) { this.shuttleInstructions = shuttleInstructions; }
    public int getNightRate() { return nightRate; }
    public void setNightRate(int nightRate) { this.nightRate = nightRate; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
