package com.flyo.rescue.integration.adapter;

import com.flyo.rescue.integration.HotelAccommodationProvider;
import com.flyo.rescue.integration.ProviderCapabilityStatus;
import com.flyo.rescue.integration.ProviderCategory;
import com.flyo.rescue.integration.ProviderMetadata;
import com.flyo.rescue.integration.dto.HotelReservationRequest;
import com.flyo.rescue.integration.dto.HotelVoucherResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class EmergencyHotelVoucherAdapter implements HotelAccommodationProvider {

    private final Environment environment;

    public EmergencyHotelVoucherAdapter(@Autowired(required = false) Environment environment) {
        this.environment = environment;
    }

    private final ProviderMetadata metadata = new ProviderMetadata(
            "emergency_hotel_voucher",
            "Emergency Airport Transit Accommodation Engine",
            ProviderCategory.HOTEL_ACCOMMODATION,
            ProviderCapabilityStatus.SANDBOX_CONTRACT_TESTED,
            List.of("REST", "JSON", "Direct Partner API"),
            1100,
            "Reserves overnight rooms at partner transit hotels and generates digital check-in vouchers for stranded passengers",
            false
    );

    @Override
    public ProviderMetadata getMetadata() {
        return metadata;
    }

    @Override
    public HotelVoucherResult reserveEmergencyAccommodation(HotelReservationRequest request) {
        if (environment != null && environment.matchesProfiles("prod")) {
            return HotelVoucherResult.failed(
                    "HOTEL_INTEGRATION_BLOCKED",
                    "Emergency hotel API integration pending client partner contract onboarding."
            );
        }

        String voucherCode = "HTL-" + request.getAirportCity() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return HotelVoucherResult.success(
                voucherCode,
                "Radisson Blu Plaza Airport " + request.getAirportCity(),
                "National Highway 8, Mahipalpur, Near IGI Airport T3",
                "Complimentary 24/7 airport shuttle departs from Terminal 3 Pillar 10 every 20 minutes.",
                3800
        );
    }
}
